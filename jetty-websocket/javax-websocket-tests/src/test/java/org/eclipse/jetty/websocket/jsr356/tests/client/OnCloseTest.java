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

import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class OnCloseTest
{
    private static class Case
    {
        public static Case add(List<Case[]> data, Class<?> closeClass)
        {
            Case tcase = new Case();
            tcase.closeClass = closeClass;
            data.add(new Case[]
                    {tcase});
            return tcase;
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
    
    @Parameters(name = "{0}")
    public static Collection<Case[]> data() throws Exception
    {
        List<Case[]> data = new ArrayList<>();
        
        Case.add(data, CloseSocket.class).expect("onClose()");
        Case.add(data, CloseReasonSocket.class).expect("onClose(CloseReason)");
        Case.add(data, CloseSessionSocket.class).expect("onClose(Session)");
        Case.add(data, CloseReasonSessionSocket.class).expect("onClose(CloseReason,Session)");
        Case.add(data, CloseSessionReasonSocket.class).expect("onClose(Session,CloseReason)");
        
        return data;
    }
    
    private final Case testcase;
    
    public OnCloseTest(Case testcase)
    {
        this.testcase = testcase;
    }
    
    @Test
    public void testOnCloseCall() throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
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
        frameHandler.onFrame(new CloseFrame().setPayload(CloseStatus.NORMAL), Callback.NOOP);

        // Test captured event
        BlockingQueue<String> events = endpoint.events;
        assertThat("Number of Events Captured", events.size(), Matchers.is(1));
        String closeEvent = events.poll(1, TimeUnit.SECONDS);
        assertThat("Close Event", closeEvent, Matchers.is(testcase.expectedCloseEvent));
    }
}
