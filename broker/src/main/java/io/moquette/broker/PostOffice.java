/*
 * Copyright (c) 2012-2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.broker;

import io.moquette.broker.scheduler.ScheduledExpirationService;
import io.moquette.broker.subscriptions.ISubscriptionsDirectory;
import io.moquette.broker.subscriptions.ShareName;
import io.moquette.broker.subscriptions.Subscription;
import io.moquette.broker.subscriptions.SubscriptionIdentifier;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.BrokerInterceptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttSubscriptionOption;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.moquette.broker.Utils.messageId;
import static io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader.from;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.EXACTLY_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.FAILURE;

class PostOffice {

    private static final String WILL_PUBLISHER = "will_publisher";
    private static final String INTERNAL_PUBLISHER = "internal_publisher";

    /**
     * Maps the failed packetID per clientId (id client source, id_packet) -> [id client target]
     * */
    private static class FailedPublishCollection {

        static class PacketId {

            private final String clientId;
            private final int idPacket;

            PacketId(String clientId, int idPacket) {
                this.clientId = clientId;
                this.idPacket = idPacket;
            }
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                PacketId packetId = (PacketId) o;
                return idPacket == packetId.idPacket && Objects.equals(clientId, packetId.clientId);
            }

            @Override
            public int hashCode() {
                return Objects.hash(clientId, idPacket);
            }

            public boolean belongToClient(String clientId) {
                return this.clientId.equals(clientId);
            }
        }

        private final ConcurrentMap<PacketId, Set<String>> packetsMap = new ConcurrentHashMap<>();

        private void insert(String clientId, int messageID, String failedClientId) {
            final PacketId packetId = new PacketId(clientId, messageID);
            packetsMap.computeIfAbsent(packetId, k -> new HashSet<>())
                .add(failedClientId);
        }

        public void remove(String clientId, int messageID, String targetClientId) {
            final PacketId packetId = new PacketId(clientId, messageID);
            packetsMap.computeIfPresent(packetId, (key, clientsSet) -> {
               clientsSet.remove(targetClientId);
               if (clientsSet.isEmpty()) {
                   // the mapping key, value is removed
                   return null;
               } else {
                   return clientsSet;
               }
            });
        }

        private void removeAll(int messageID, String clientId, Collection<String> routings) {
            for (String targetClientId : routings) {
                remove(clientId, messageID, targetClientId);
            }
        }

        void cleanupForClient(String clientId) {
            // the only way to linear scan the map to collect of PacketIds references,
            // for a following step of cleaning
            packetsMap.keySet().stream()
                .filter(packet -> packet.belongToClient(clientId))
                .forEach(packetsMap::remove);
        }

        void insertAll(int messageID, String clientId, Collection<String> routings) {
            for (String targetClientId : routings) {
                insert(clientId, messageID, targetClientId);
            }
        }

        Set<String> listFailed(String clientId, int messageID) {
            final PacketId packetId = new PacketId(clientId, messageID);
            return packetsMap.getOrDefault(packetId, Collections.emptySet());
        }
    }

    static class RouteResult {
        private final String clientId;
        private final Status status;
        private CompletableFuture queuedFuture;

        enum Status {SUCCESS, FAIL}

        public static RouteResult success(String clientId, CompletableFuture queuedFuture) {
            return new RouteResult(clientId, Status.SUCCESS, queuedFuture);
        }

        public static RouteResult failed(String clientId) {
            return failed(clientId, null);
        }

        public static RouteResult failed(String clientId, String error) {
           final CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new Error(error));
            return new RouteResult(clientId, Status.FAIL, failed);
        }

        private RouteResult(String clientId, Status status, CompletableFuture queuedFuture) {
            this.clientId = clientId;
            this.status = status;
            this.queuedFuture = queuedFuture;
        }

        public CompletableFuture completableFuture() {
            if (status == Status.FAIL) {
                throw new IllegalArgumentException("Accessing completable future on a failed result");
            }
            return queuedFuture;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public RouteResult ifFailed(Runnable action) {
            if (!isSuccess()) {
                action.run();
            }
            return this;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PostOffice.class);

    private static final Set<String> NO_FILTER = new HashSet<>();

    private final Authorizator authorizator;
    private final ISubscriptionsDirectory subscriptions;
    private final IRetainedRepository retainedRepository;
    private final ISessionsRepository sessionRepository;
    private SessionRegistry sessionRegistry;
    private BrokerInterceptor interceptor;
    private final FailedPublishCollection failedPublishes = new FailedPublishCollection();
    private final SessionEventLoopGroup sessionLoops;
    private final Clock clock;
    private final ScheduledExpirationService<ISessionsRepository.Will> willExpirationService;
    private final MqttQoS maxServerGrantedQos;

    /**
     * Used only in tests
     * */
    PostOffice(ISubscriptionsDirectory subscriptions, IRetainedRepository retainedRepository,
               SessionRegistry sessionRegistry, ISessionsRepository sessionRepository, BrokerInterceptor interceptor, Authorizator authorizator,
               SessionEventLoopGroup sessionLoops) {
        this(subscriptions, retainedRepository, sessionRegistry, sessionRepository, interceptor, authorizator, sessionLoops, Clock.systemDefaultZone());
    }

    PostOffice(ISubscriptionsDirectory subscriptions, IRetainedRepository retainedRepository,
               SessionRegistry sessionRegistry, ISessionsRepository sessionRepository, BrokerInterceptor interceptor,
               Authorizator authorizator,
               SessionEventLoopGroup sessionLoops, Clock clock) {
        this(subscriptions, retainedRepository, sessionRegistry, sessionRepository, interceptor, authorizator,
            sessionLoops, clock, EXACTLY_ONCE);
    }

    PostOffice(ISubscriptionsDirectory subscriptions, IRetainedRepository retainedRepository,
               SessionRegistry sessionRegistry, ISessionsRepository sessionRepository, BrokerInterceptor interceptor,
               Authorizator authorizator,
               SessionEventLoopGroup sessionLoops, Clock clock, MqttQoS maxServerGrantedQos) {
        this.authorizator = authorizator;
        this.subscriptions = subscriptions;
        this.retainedRepository = retainedRepository;
        this.sessionRepository = sessionRepository;
        this.sessionRegistry = sessionRegistry;
        this.interceptor = interceptor;
        this.sessionLoops = sessionLoops;
        this.clock = clock;
        this.maxServerGrantedQos = maxServerGrantedQos;

        this.willExpirationService = new ScheduledExpirationService<>(clock, this::publishWill);
        recreateWillExpires(sessionRepository);
    }

    private void recreateWillExpires(ISessionsRepository sessionRepository) {
        sessionRepository.listSessionsWill(willExpirationService::track);
    }

    public void fireWill(Session bindedSession) {
        final ISessionsRepository.Will will =  bindedSession.getWill();
        final String clientId = bindedSession.getClientID();

        if (will.delayInterval == 0) {
            // if interval is 0 fire immediately
            // MQTT3  3.1.2.8-17
            publishWill(will);
        } else {
            // MQTT5 MQTT-3.1.3-9
            final int executionInterval = Math.min(bindedSession.getSessionData().expiryInterval(), will.delayInterval);

            trackWillSpecificationForFutureFire(bindedSession, will, clientId, executionInterval);

            LOG.debug("Scheduled will message for client {} on topic {}", clientId, will.topic);
        }
    }

    private void trackWillSpecificationForFutureFire(Session bindedSession, ISessionsRepository.Will will, String clientId, int executionInterval) {
        // Update Session's SessionData with a new Will with computed expiration
        final ISessionsRepository.Will willWithEOL = will.withExpirationComputed(executionInterval, clock);
        // save the will in the will store
        sessionRepository.saveWill(bindedSession.getClientID(), willWithEOL);
        willExpirationService.track(clientId, willWithEOL);
    }

    private void publishWill(ISessionsRepository.Will will) {
        publish2Subscribers(WILL_PUBLISHER, Unpooled.copiedBuffer(will.payload), new Topic(will.topic), will.qos, will.retained);
    }

    /**
     * Wipe the eventual existing will delayed publish tasks.
     *
     * @param clientId session's Id.
     * */
    public void wipeExistingScheduledWill(String clientId) {
        if (willExpirationService.untrack(clientId)) {
            LOG.debug("Wiped task to delayed publish for old client {}", clientId);
        }

        sessionRepository.deleteWill(clientId);
    }

    // Used for internal purposes of subscribeClientToTopics method
    private static final class SharedSubscriptionData {
        final ShareName name;
        final Topic topicFilter;
        final MqttSubscriptionOption option;

        private SharedSubscriptionData(ShareName name, Topic topicFilter, MqttSubscriptionOption option) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(topicFilter);
            Objects.requireNonNull(option);
            this.name = name;
            this.topicFilter = topicFilter;
            this.option = option;
        }

        static SharedSubscriptionData fromMqttSubscription(MqttTopicSubscription sub) {
            return new SharedSubscriptionData(new ShareName(SharedSubscriptionUtils.extractShareName(sub.topicName())),
                Topic.asTopic(SharedSubscriptionUtils.extractFilterFromShared(sub.topicName())), sub.option());
        }
    }

    public void subscribeClientToTopics(MqttSubscribeMessage msg, String clientID, String username,
                                        MQTTConnection mqttConnection) {
        // verify which topics of the subscribe ongoing has read access permission
        int messageID = messageId(msg);
        final Session session = sessionRegistry.retrieve(clientID);

        final List<SharedSubscriptionData> sharedSubscriptions;
        final Optional<SubscriptionIdentifier> subscriptionIdOpt;

        if (mqttConnection.isProtocolVersion5()) {
            sharedSubscriptions = msg.payload().topicSubscriptions().stream()
                .filter(sub -> SharedSubscriptionUtils.isSharedSubscription(sub.topicName()))
                .map(SharedSubscriptionData::fromMqttSubscription)
                .collect(Collectors.toList());

            Optional<SharedSubscriptionData> invalidSharedSubscription = sharedSubscriptions.stream()
                .filter(subData -> !SharedSubscriptionUtils.validateShareName(subData.name.toString()))
                .findFirst();
            if (invalidSharedSubscription.isPresent()) {
                // this is a malformed packet, MQTT-4.13.1-1, disconnect it
                LOG.info("{} used an invalid shared subscription name {}, disconnecting", clientID, invalidSharedSubscription.get().name);
                session.disconnectFromBroker();
                return;
            }

            try {
                subscriptionIdOpt = verifyAndExtractMessageIdentifier(msg);
            } catch (IllegalArgumentException ex) {
                session.disconnectFromBroker();
                return;
            }
        } else {
            sharedSubscriptions = Collections.emptyList();
            subscriptionIdOpt = Optional.empty();
        }

        List<MqttTopicSubscription> ackTopics;
        if (mqttConnection.isProtocolVersion5()) {
            ackTopics = authorizator.verifyAlsoSharedTopicsReadAccess(clientID, username, msg);
        } else {
            ackTopics = authorizator.verifyTopicsReadAccess(clientID, username, msg);
        }
        ackTopics = updateWithMaximumSupportedQoS(ackTopics);
        MqttSubAckMessage ackMessage = doAckMessageFromValidateFilters(ackTopics, messageID);

        // store topics of non-shared subscriptions in session
        List<Subscription> newSubscriptions = ackTopics.stream()
            .filter(sub -> sub.qualityOfService() != FAILURE)
            .filter(sub -> !SharedSubscriptionUtils.isSharedSubscription(sub.topicName()))
            .map(sub -> {
                final Topic topic = new Topic(sub.topicName());
                MqttSubscriptionOption option = sub.option();//MqttSubscriptionOption.onlyFromQos(sub.qualityOfService());
                if (subscriptionIdOpt.isPresent()) {
                    return new Subscription(clientID, topic, option, subscriptionIdOpt.get());
                } else {
                    return new Subscription(clientID, topic, option);
                }
            }).collect(Collectors.toList());

        final Set<Subscription> subscriptionToSendRetained = newSubscriptions.stream()
            .map(this::addSubscriptionReportingNewStatus) // mutating operation of SubscriptionDirectory
            .filter(PostOffice::needToReceiveRetained)
            .map(couple -> couple.v2)
            .collect(Collectors.toSet());

        for (SharedSubscriptionData sharedSubData : sharedSubscriptions) {
            if (subscriptionIdOpt.isPresent()) {
                subscriptions.addShared(clientID, sharedSubData.name, sharedSubData.topicFilter, sharedSubData.option,
                    subscriptionIdOpt.get());
            } else {
                subscriptions.addShared(clientID, sharedSubData.name, sharedSubData.topicFilter, sharedSubData.option);
            }
        }

        // add the subscriptions to Session
        session.addSubscriptions(newSubscriptions);

        // send ack message
        mqttConnection.sendSubAckMessage(messageID, ackMessage);

        // shared subscriptions doesn't receive retained messages
        publishRetainedMessagesForSubscriptions(clientID, subscriptionToSendRetained);

        for (Subscription subscription : newSubscriptions) {
            interceptor.notifyTopicSubscribed(subscription, username);
        }
    }

    private static boolean needToReceiveRetained(Utils.Couple<Boolean, Subscription> addedAndSub) {
        MqttSubscriptionOption subOptions = addedAndSub.v2.option();
        switch (subOptions.retainHandling()) {
            case SEND_AT_SUBSCRIBE:
                return true;
            case SEND_AT_SUBSCRIBE_IF_NOT_YET_EXISTS:
                if (addedAndSub.v1) {
                    return true;
                }
            default:
                return false;
        }
    }

    private Utils.Couple<Boolean, Subscription> addSubscriptionReportingNewStatus(Subscription subscription) {
        final boolean newlyAdded;
        if (subscription.hasSubscriptionIdentifier()) {
            SubscriptionIdentifier subscriptionId = subscription.getSubscriptionIdentifier();
            newlyAdded = subscriptions.add(subscription.getClientId(), subscription.getTopicFilter(),
                subscription.option(), subscriptionId);
        } else {
            newlyAdded = subscriptions.add(subscription.getClientId(), subscription.getTopicFilter(), subscription.option());
        }
        return new Utils.Couple<>(newlyAdded, subscription);
    }

    private List<MqttTopicSubscription> updateWithMaximumSupportedQoS(List<MqttTopicSubscription> subscriptions) {
        return subscriptions.stream()
            .map(this::updateWithMaximumSupportedQoS)
            .collect(Collectors.toList());
    }

    private MqttTopicSubscription updateWithMaximumSupportedQoS(MqttTopicSubscription s) {
        MqttQoS grantedQos = minQos(s.qualityOfService(), maxServerGrantedQos);
        MqttSubscriptionOption option = optionWithQos(grantedQos, s.option());
        return new MqttTopicSubscription(s.topicName(), option);
    }

    static MqttSubscriptionOption optionWithQos(MqttQoS grantedQos, MqttSubscriptionOption option) {
        return new MqttSubscriptionOption(grantedQos, option.isNoLocal(), option.isRetainAsPublished(), option.retainHandling());
    }

    private static MqttQoS minQos(MqttQoS q1, MqttQoS q2) {
        if (q1 == FAILURE || q2 == FAILURE) {
            return FAILURE;
        }
        return q1.value() < q2.value() ? q1 : q2;
    }

    private static Optional<SubscriptionIdentifier> verifyAndExtractMessageIdentifier(MqttSubscribeMessage msg) {
        final List<MqttProperties.MqttProperty<Integer>> subscriptionIdentifierProperties =
            (List<MqttProperties.MqttProperty<Integer>>) msg.idAndPropertiesVariableHeader().properties()
                .getProperties(MqttProperties.MqttPropertyType.SUBSCRIPTION_IDENTIFIER.value());
        if (subscriptionIdentifierProperties.size() > 1) {
            // more than 1 SUBSCRIPTION_IDENTIFIER property during subscribe is a protocol error
            LOG.warn("Received a Subscribe with more than one subscription identifier property ({})", subscriptionIdentifierProperties.size());
            throw new IllegalArgumentException("More than one subscription identifier properties");
        }
        if (subscriptionIdentifierProperties.isEmpty()) {
            return Optional.empty();
        }
        Integer value = subscriptionIdentifierProperties.iterator().next().value();
        try {
            return Optional.of(new SubscriptionIdentifier(value));
        } catch (IllegalArgumentException ex) {
            LOG.warn("Received a Subscribe with SubscriptionIdentifier value {} out of range 1..268435455", value);
            throw ex;
        }
    }

    private void publishRetainedMessagesForSubscriptions(String clientID, Collection<Subscription> newSubscriptions) {
        Session targetSession = this.sessionRegistry.retrieve(clientID);
        for (Subscription subscription : newSubscriptions) {
            final String topicFilter = subscription.getTopicFilter().toString();
            final Collection<RetainedMessage> retainedMsgs = retainedRepository.retainedOnTopic(topicFilter);

            if (retainedMsgs.isEmpty()) {
                LOG.info("No retained messages matching topic filter {}", topicFilter);
                continue;
            }
            final MqttProperties.MqttProperty[] properties = prepareSubscriptionProperties(subscription);
            for (RetainedMessage retainedMsg : retainedMsgs) {
                final MqttQoS retainedQos = retainedMsg.qosLevel();
                MqttQoS qos = lowerQosToTheSubscriptionDesired(subscription, retainedQos);

                final ByteBuf payloadBuf = Unpooled.wrappedBuffer(retainedMsg.getPayload());
                targetSession.sendRetainedPublishOnSessionAtQos(retainedMsg.getTopic(), qos, payloadBuf, properties);
                // We made the buffer, we must release it.
                payloadBuf.release();
            }
        }
    }

    /**
     * Create the SUBACK response from a list of topicFilters
     */
    private MqttSubAckMessage doAckMessageFromValidateFilters(List<MqttTopicSubscription> topicFilters, int messageId) {
        List<Integer> grantedQoSLevels = new ArrayList<>();
        for (MqttTopicSubscription req : topicFilters) {
            grantedQoSLevels.add(req.qualityOfService().value());
        }

        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.SUBACK, false, AT_MOST_ONCE,
            false, 0);
        MqttSubAckPayload payload = new MqttSubAckPayload(grantedQoSLevels);
        return new MqttSubAckMessage(fixedHeader, from(messageId), payload);
    }

    public void unsubscribe(List<String> topics, MQTTConnection mqttConnection, int messageId) {
        final String clientID = mqttConnection.getClientId();
        final Session session = sessionRegistry.retrieve(clientID);
        if (session == null) {
            // Session is already destroyed.
            // Just ack the client
            LOG.warn("Session not found when unsubscribing {}", clientID);
            mqttConnection.sendUnsubAckMessage(topics, clientID, messageId);
            return;
        }
        for (String t : topics) {
            Topic topic = new Topic(t);
            boolean validTopic = topic.isValid();
            if (!validTopic) {
                // close the connection, not valid topicFilter is a protocol violation
                mqttConnection.dropConnection();
                LOG.warn("Topic filter is not valid. topics: {}, offending topic filter: {}", topics, topic);
                return;
            }

            LOG.trace("Removing subscription topic={}", topic);
            if (SharedSubscriptionUtils.isSharedSubscription(t)) {
                String topicFilterPart = SharedSubscriptionUtils.extractFilterFromShared(t);
                ShareName shareName = new ShareName(SharedSubscriptionUtils.extractShareName(t));
                subscriptions.removeSharedSubscription(shareName, Topic.asTopic(topicFilterPart), clientID);
            } else {
                subscriptions.removeSubscription(topic, clientID);
            }

            session.removeSubscription(topic);

            String username = NettyUtils.userName(mqttConnection.channel);
            interceptor.notifyTopicUnsubscribed(topic.toString(), clientID, username);
        }

        // ack the client
        mqttConnection.sendUnsubAckMessage(topics, clientID, messageId);
    }

    CompletableFuture<Void> receivedPublishQos0(Topic topic, String username, String clientID, MqttPublishMessage msg) {
        if (!authorizator.canWrite(topic, username, clientID)) {
            LOG.error("client is not authorized to publish on topic: {}", topic);
            ReferenceCountUtil.release(msg);
            return CompletableFuture.completedFuture(null);
        }
        final RoutingResults publishResult = publish2Subscribers(clientID, msg.payload(), topic, AT_MOST_ONCE, msg.fixedHeader().isRetain());
        if (publishResult.isAllFailed()) {
            LOG.info("No one publish was successfully enqueued to session loops");
            ReferenceCountUtil.release(msg);
            return CompletableFuture.completedFuture(null);
        }

        return publishResult.completableFuture().thenRun(() -> {
            if (msg.fixedHeader().isRetain()) {
                // QoS == 0 && retain => clean old retained
                retainedRepository.cleanRetained(topic);
            }

            interceptor.notifyTopicPublished(msg, clientID, username);
            ReferenceCountUtil.release(msg);
        });
    }

    RoutingResults receivedPublishQos1(MQTTConnection connection, Topic topic, String username, int messageID,
                                       MqttPublishMessage msg) {
        // verify if topic can be written
        topic.getTokens();
        if (!topic.isValid()) {
            LOG.warn("Invalid topic format, force close the connection");
            connection.dropConnection();
            ReferenceCountUtil.release(msg);
            return RoutingResults.preroutingError();
        }
        final String clientId = connection.getClientId();
        if (!authorizator.canWrite(topic, username, clientId)) {
            LOG.error("MQTT client: {} is not authorized to publish on topic: {}", clientId, topic);
            ReferenceCountUtil.release(msg);
            return RoutingResults.preroutingError();
        }

        ByteBuf payload = msg.payload();
        boolean retainPublish = msg.fixedHeader().isRetain();
        final RoutingResults routes;
        if (msg.fixedHeader().isDup()) {
            final Set<String> failedClients = failedPublishes.listFailed(clientId, messageID);
            routes = publish2Subscribers(clientId, payload, topic, AT_LEAST_ONCE, failedClients, retainPublish);
        } else {
            routes = publish2Subscribers(clientId, payload, topic, AT_LEAST_ONCE, retainPublish);
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("subscriber routes: {}", routes);
        }
        if (routes.isAllSuccess()) {
            // QoS1 message was enqueued successfully to every event loop
            connection.sendPubAck(messageID);
            manageRetain(topic, msg);
            interceptor.notifyTopicPublished(msg, clientId, username);
        } else {
            // some session event loop enqueue raised a problem
            failedPublishes.insertAll(messageID, clientId, routes.failedRoutings);
        }
        ReferenceCountUtil.release(msg);

        // cleanup success resends from the failed publishes cache
        failedPublishes.removeAll(messageID, clientId, routes.successedRoutings);

        return routes;
    }

    private void manageRetain(Topic topic, MqttPublishMessage msg) {
        if (msg.fixedHeader().isRetain()) {
            if (!msg.payload().isReadable()) {
                retainedRepository.cleanRetained(topic);
            } else {
                // before wasn't stored
                retainedRepository.retain(topic, msg);
            }
        }
    }

    private RoutingResults publish2Subscribers(String publisherClientId, ByteBuf payload, Topic topic,
                                               MqttQoS publishingQos, boolean isPublishRetained) {
        return publish2Subscribers(publisherClientId, payload, topic, publishingQos, NO_FILTER, isPublishRetained);
    }

    private class BatchingPublishesCollector {
        final List<Subscription>[] subscriptions;
        private final int eventLoops;
        private final SessionEventLoopGroup loopGroup;

        BatchingPublishesCollector(SessionEventLoopGroup loopGroup) {
            eventLoops = loopGroup.getEventLoopCount();
            this.loopGroup = loopGroup;
            subscriptions = new List[eventLoops];
        }

        public void add(Subscription sub) {
            final int targetQueueId = subscriberEventLoop(sub.getClientId());
            if (subscriptions[targetQueueId] == null) {
                subscriptions[targetQueueId] = new ArrayList<>();
            }
            subscriptions[targetQueueId].add(sub);
        }

        private int subscriberEventLoop(String clientId) {
            return loopGroup.targetQueueOrdinal(clientId);
        }

        List<RouteResult> routeBatchedPublishes(Consumer<List<Subscription>> action) {
            List<RouteResult> publishResults = new ArrayList<>(this.eventLoops);

            for (List<Subscription> subscriptionsBatch : subscriptions) {
                if (subscriptionsBatch == null) {
                    continue;
                }
                final String clientId = subscriptionsBatch.get(0).getClientId();
                if (LOG.isTraceEnabled()) {
                    final String subscriptionsDetails = subscriptionsBatch.stream()
                        .map(Subscription::toString)
                        .collect(Collectors.joining(",\n"));
                    final int loopId = subscriberEventLoop(clientId);
                    LOG.trace("Routing PUBLISH to eventLoop {}  for subscriptions [{}]", loopId, subscriptionsDetails);
                }
                publishResults.add(routeCommand(clientId, "batched PUB", () -> {
                    action.accept(subscriptionsBatch);
                    return null;
                }));
            }
            return publishResults;
        }

        Collection<String> subscriberIdsByEventLoop(String clientId) {
            final int targetQueueId = subscriberEventLoop(clientId);
            return subscriptions[targetQueueId].stream().map(Subscription::getClientId).collect(Collectors.toList());
        }

        public int countBatches() {
            int count = 0;
            for (List<Subscription> subscriptionsBatch : subscriptions) {
                if (subscriptionsBatch == null) {
                    continue;
                }
                count ++;
            }
            return count;
        }
    }

    private RoutingResults publish2Subscribers(String publisherClientId, ByteBuf payload, Topic topic, MqttQoS publishingQos,
                                               Set<String> filterTargetClients, boolean retainPublish) {
        List<Subscription> topicMatchingSubscriptions = subscriptions.matchQosSharpening(topic);
        if (topicMatchingSubscriptions.isEmpty()) {
            // no matching subscriptions, clean exit
            LOG.trace("No matching subscriptions for topic: {}", topic);
            return new RoutingResults(Collections.emptyList(), Collections.emptyList(), CompletableFuture.completedFuture(null));
        }

        final BatchingPublishesCollector collector = new BatchingPublishesCollector(sessionLoops);

        for (final Subscription sub : topicMatchingSubscriptions) {
            if (filterTargetClients == NO_FILTER || filterTargetClients.contains(sub.getClientId())) {
                if (sub.option().isNoLocal()) {
                    if (publisherClientId.equals(sub.getClientId())) {
                        // if noLocal do not publish to the publisher
                        continue;
                    }
                    collector.add(sub);
                } else {
                    collector.add(sub);
                }
            }
        }

        int subscriptionCount = collector.countBatches();
        if (subscriptionCount <= 0) {
            // no matching subscriptions, clean exit
            LOG.trace("No matching subscriptions for topic: {}", topic);
            return new RoutingResults(Collections.emptyList(), Collections.emptyList(), CompletableFuture.completedFuture(null));
        }

        payload.retain(subscriptionCount);

        List<RouteResult> publishResults = collector.routeBatchedPublishes((batch) -> {
            publishToSession(payload, topic, batch, publishingQos, retainPublish);
            payload.release();
        });

        final CompletableFuture[] publishFutures = publishResults.stream()
            .filter(RouteResult::isSuccess)
            .map(RouteResult::completableFuture).toArray(CompletableFuture[]::new);
        final CompletableFuture<Void> publishes = CompletableFuture.allOf(publishFutures);

        final List<String> failedRoutings = new ArrayList<>();
        final List<String> successedRoutings = new ArrayList<>();
        for (RouteResult rr : publishResults) {
            Collection<String> subscibersIds = collector.subscriberIdsByEventLoop(rr.clientId);
            if (rr.status == RouteResult.Status.FAIL) {
                failedRoutings.addAll(subscibersIds);
                payload.release();
            } else {
                successedRoutings.addAll(subscibersIds);
            }
        }
        return new RoutingResults(successedRoutings, failedRoutings, publishes);
    }

    private void publishToSession(ByteBuf payload, Topic topic, Collection<Subscription> subscriptions,
                                  MqttQoS publishingQos, boolean retainPublish) {
        ByteBuf duplicatedPayload = payload.duplicate();
        for (Subscription sub : subscriptions) {
            MqttQoS qos = lowerQosToTheSubscriptionDesired(sub, publishingQos);
            boolean retained = false;
            if (sub.option().isRetainAsPublished()) {
                retained = retainPublish;
            }
            publishToSession(duplicatedPayload, topic, sub, qos, retained);
        }
    }

    private void publishToSession(ByteBuf payload, Topic topic, Subscription sub, MqttQoS qos, boolean retained) {
        Session targetSession = this.sessionRegistry.retrieve(sub.getClientId());

        boolean isSessionPresent = targetSession != null;
        if (isSessionPresent) {
            LOG.debug("Sending PUBLISH message to active subscriber CId: {}, topicFilter: {}, qos: {}",
                      sub.getClientId(), sub.getTopicFilter(), qos);
            final MqttProperties.MqttProperty[] properties = prepareSubscriptionProperties(sub);
            targetSession.sendPublishOnSessionAtQos(topic, qos, payload, retained, properties);
        } else {
            // If we are, the subscriber disconnected after the subscriptions tree selected that session as a
            // destination.
            LOG.debug("PUBLISH to not yet present session. CId: {}, topicFilter: {}, qos: {}", sub.getClientId(),
                      sub.getTopicFilter(), qos);
        }
    }

    private MqttProperties.MqttProperty[] prepareSubscriptionProperties(Subscription sub) {
        MqttProperties.MqttProperty[] properties;
        if (sub.hasSubscriptionIdentifier()) {
            MqttProperties.IntegerProperty subscriptionId = createSubscriptionIdProperty(sub);
            properties = new MqttProperties.MqttProperty[] { subscriptionId };
        } else {
            properties = new MqttProperties.MqttProperty[0];
        }
        return properties;
    }

    private MqttProperties.IntegerProperty createSubscriptionIdProperty(Subscription sub) {
        int subscriptionId = sub.getSubscriptionIdentifier().value();
        return new MqttProperties.IntegerProperty(MqttProperties.MqttPropertyType.SUBSCRIPTION_IDENTIFIER.value(), subscriptionId);
    }

    /**
     * First phase of a publish QoS2 protocol, sent by publisher to the broker. Publish to all interested
     * subscribers.
     * @return
     */
    RoutingResults receivedPublishQos2(MQTTConnection connection, MqttPublishMessage msg, String username) {
        LOG.trace("Processing PUB QoS2 message on connection: {}", connection);
        final Topic topic = new Topic(msg.variableHeader().topicName());
        final ByteBuf payload = msg.payload();

        final String clientId = connection.getClientId();
        if (!authorizator.canWrite(topic, username, clientId)) {
            LOG.error("MQTT client is not authorized to publish on topic: {}", topic);
            ReferenceCountUtil.release(msg);
            // WARN this is a special case failed is empty, but this result is to be considered as error.
            return RoutingResults.preroutingError();
        }

        final int messageID = msg.variableHeader().packetId();
        boolean retainPublish = msg.fixedHeader().isRetain();
        final RoutingResults publishRoutings;
        if (msg.fixedHeader().isDup()) {
            final Set<String> failedClients = failedPublishes.listFailed(clientId, messageID);
            publishRoutings = publish2Subscribers(clientId, payload, topic, EXACTLY_ONCE, failedClients, retainPublish);
        } else {
            publishRoutings = publish2Subscribers(clientId, payload, topic, EXACTLY_ONCE, retainPublish);
        }
        if (publishRoutings.isAllSuccess()) {
            // QoS2 PUB message was enqueued successfully to every event loop
            connection.sendPubRec(messageID);
            manageRetain(topic, msg);
            interceptor.notifyTopicPublished(msg, clientId, username);
        } else {
            // some session event loop enqueue raised a problem
            failedPublishes.insertAll(messageID, clientId, publishRoutings.failedRoutings);
        }
        ReferenceCountUtil.release(msg);

        // cleanup success resends from the failed publishes cache
        failedPublishes.removeAll(messageID, clientId, publishRoutings.successedRoutings);

        return publishRoutings;
    }

    static MqttQoS lowerQosToTheSubscriptionDesired(Subscription sub, MqttQoS qos) {
        if (qos.value() > sub.option().qos().value()) {
            qos = sub.option().qos();
        }
        return qos;
    }

    /**
     * Intended usage is only for embedded versions of the broker, where the hosting application
     * want to use the broker to send a publish message. Like normal external publish message but
     * with some changes to avoid security check, and the handshake phases for Qos1 and Qos2. It
     * also doesn't notifyTopicPublished because using internally the owner should already know
     * where it's publishing.
     *
     * @param msg      the message to publish
     * @return the result of the enqueuing operation to session loops.
     */
    public RoutingResults internalPublish(MqttPublishMessage msg) {
        final MqttQoS qos = msg.fixedHeader().qosLevel();
        final Topic topic = new Topic(msg.variableHeader().topicName());
        final ByteBuf payload = msg.payload();
        LOG.info("Sending internal PUBLISH message Topic={}, qos={}", topic, qos);

        boolean retainPublish = msg.fixedHeader().isRetain();
        final RoutingResults publishResult = publish2Subscribers(INTERNAL_PUBLISHER, payload, topic, qos, retainPublish);
        LOG.trace("after routed publishes: {}", publishResult);

        if (!retainPublish) {
            return publishResult;
        }
        if (qos == AT_MOST_ONCE || payload.readableBytes() == 0) {
            // QoS == 0 && retain => clean old retained
            retainedRepository.cleanRetained(topic);
            return publishResult;
        }
        retainedRepository.retain(topic, msg);
        return publishResult;
    }

    /**
     * notify MqttConnectMessage after connection established (already pass login).
     * @param msg
     */
    void dispatchConnection(MqttConnectMessage msg) {
        interceptor.notifyClientConnected(msg);
    }

    void dispatchDisconnection(String clientId,String userName) {
        interceptor.notifyClientDisconnected(clientId, userName);
    }

    void dispatchConnectionLost(String clientId,String userName) {
        interceptor.notifyClientConnectionLost(clientId, userName);
    }

    String sessionLoopThreadName(String clientId) {
        return sessionLoops.sessionLoopThreadName(clientId);
    }

    /**
     * Route the command to the owning SessionEventLoop
     * */
    public RouteResult routeCommand(String clientId, String actionDescription, Callable<Void> action) {
        return sessionLoops.routeCommand(clientId, actionDescription, action);
    }

    public void terminate() {
        willExpirationService.shutdown();
        sessionLoops.terminate();
    }

    /**
     * Clean up all the data related to the specified client;
     * */
    public void clientDisconnected(String clientID, String userName) {
        dispatchDisconnection(clientID, userName);
        this.failedPublishes.cleanupForClient(clientID);
    }
}
