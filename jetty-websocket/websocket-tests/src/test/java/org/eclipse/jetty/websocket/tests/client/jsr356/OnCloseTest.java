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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    
    private static ClientContainer container = new ClientContainer();
    
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
        // Build up EventDriver
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        TrackingSocket endpoint = (TrackingSocket) testcase.closeClass.newInstance();
        
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
            assertThat("Close Event", closeEvent, is(testcase.expectedCloseEvent));
        }
        finally
        {
            jsrFunctions.stop();
        }
    }
}
