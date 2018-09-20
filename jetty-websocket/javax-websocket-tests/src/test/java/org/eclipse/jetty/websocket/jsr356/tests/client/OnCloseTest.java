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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.jsr356.tests.DummyChannel;
import org.eclipse.jetty.websocket.jsr356.tests.HandshakeRequestAdapter;
import org.eclipse.jetty.websocket.jsr356.tests.HandshakeResponseAdapter;
import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;
import org.eclipse.jetty.websocket.jsr356.tests.client.samples.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jsr356.tests.client.samples.CloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.tests.client.samples.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jsr356.tests.client.samples.CloseSessionSocket;
import org.eclipse.jetty.websocket.jsr356.tests.client.samples.CloseSocket;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;

public class OnCloseTest
{
    private static class Case
    {
        public static Case add(List<Case[]> data, Class<?> closeClass)
        {
            Case tcase = new Case(closeClass);
            data.add(new Case[]{tcase});
            return tcase;
        }
        
        public Case(Class<?> closeClass)
        {
            this.closeClass = closeClass;
        }
        
        Class<?> closeClass;
        String expectedCloseEvent;
        
        public Case expect(String expectedEvent)
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
    
    private static JavaxWebSocketClientContainer container = new JavaxWebSocketClientContainer();

    public static Stream<Arguments> data() throws Exception
    {
        return Stream.of(
            Arguments.of(new Case(CloseSocket.class).expect("onClose()")),
            Arguments.of(new Case(CloseReasonSocket.class).expect("onClose(CloseReason)")),
            Arguments.of(new Case(CloseSessionSocket.class).expect("onClose(Session)")),
            Arguments.of(new Case(CloseReasonSessionSocket.class).expect("onClose(CloseReason,Session)")),
            Arguments.of(new Case(CloseSessionReasonSocket.class).expect("onClose(Session,CloseReason)"))
        );
    }
    
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testOnCloseCall(Case testcase) throws Exception
    {
        WebSocketPolicy policy = new WebSocketPolicy();
        WSEventTracker endpoint = (WSEventTracker) testcase.closeClass.newInstance();
        
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        // TODO: use ConfiguredEndpoint here?

        JavaxWebSocketClientContainer container = new JavaxWebSocketClientContainer();
        container.start();

        HandshakeRequest request = new HandshakeRequestAdapter();
        HandshakeResponse response = new HandshakeResponseAdapter();
        CompletableFuture<Session> futureSession = new CompletableFuture<>();

        JavaxWebSocketFrameHandler frameHandler = container.newFrameHandler(endpoint, policy, request, response, futureSession);
        frameHandler.onOpen(new DummyChannel());

        // Execute onClose call
        frameHandler.onReceiveFrame(CloseStatus.toFrame(CloseStatus.NORMAL), Callback.NOOP);

        // Test captured event
        BlockingQueue<String> events = endpoint.events;
        assertThat("Number of Events Captured", events.size(), Matchers.is(1));
        String closeEvent = events.poll(1, TimeUnit.SECONDS);
        assertThat("Close Event", closeEvent, Matchers.is(testcase.expectedCloseEvent));
    }
}
