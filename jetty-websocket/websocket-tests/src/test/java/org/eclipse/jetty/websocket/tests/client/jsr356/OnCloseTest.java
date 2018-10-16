//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions;
import org.eclipse.jetty.websocket.tests.client.jsr356.samples.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.tests.client.jsr356.samples.CloseReasonSocket;
import org.eclipse.jetty.websocket.tests.client.jsr356.samples.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.tests.client.jsr356.samples.CloseSessionSocket;
import org.eclipse.jetty.websocket.tests.client.jsr356.samples.CloseSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.TrackingSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OnCloseTest
{
    private static class Scenario
    {
        public static Scenario add(List<Arguments> data, Class<?> closeClass)
        {
            Scenario scenario = new Scenario();
            scenario.closeClass = closeClass;
            data.add(Arguments.of(scenario));
            return scenario;
        }

        Class<?> closeClass;
        String expectedCloseEvent;

        public Scenario expect(String expectedEvent)
        {
            this.expectedCloseEvent = expectedEvent;
            return this;
        }

        @Override
        public String toString()
        {
            return closeClass.getSimpleName();
        }
    }

    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();

        Scenario.add(data, CloseSocket.class).expect("onClose()");
        Scenario.add(data, CloseReasonSocket.class).expect("onClose(CloseReason)");
        Scenario.add(data, CloseSessionSocket.class).expect("onClose(Session)");
        Scenario.add(data, CloseReasonSessionSocket.class).expect("onClose(CloseReason,Session)");
        Scenario.add(data, CloseSessionReasonSocket.class).expect("onClose(Session,CloseReason)");

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testOnCloseCall(Scenario scenario) throws Exception
    {
        // Build up EventDriver
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        TrackingSocket endpoint = (TrackingSocket) scenario.closeClass.newInstance();

        Executor executor = new QueuedThreadPool();
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        AvailableEncoders encoders = new AvailableEncoders(config);
        AvailableDecoders decoders = new AvailableDecoders(config);
        Map<String, String> uriParams = new HashMap<>();

        JsrEndpointFunctions jsrFunctions = new JsrEndpointFunctions(endpoint, policy,
                executor, encoders, decoders, uriParams, config);
        try
        {
            jsrFunctions.start();

            // Execute onClose call
            jsrFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "normal"));

            // Test captured event
            BlockingQueue<String> events = endpoint.events;
            assertThat("Number of Events Captured", events.size(), is(1));
            String closeEvent = events.poll(1, TimeUnit.SECONDS);
            assertThat("Close Event", closeEvent, is(scenario.expectedCloseEvent));
        }
        finally
        {
            jsrFunctions.stop();
        }
    }
}
