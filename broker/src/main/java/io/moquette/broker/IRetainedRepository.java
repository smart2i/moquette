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

import io.moquette.broker.subscriptions.Topic;
import io.netty.handler.codec.mqtt.MqttPublishMessage;

import java.util.Collection;
import java.util.List;

public interface IRetainedRepository {

    void cleanRetained(Topic topic);

    void retain(Topic topic, MqttPublishMessage msg);

    boolean isEmpty();

    /**
     * Return the list of messages retained on a specified topic.
     *
     * @param topic the topic containing the retained messages.
     * @return the unordered collection of retained messages on the topic.
     * */
    Collection<RetainedMessage> retainedOnTopic(String topic);
}
