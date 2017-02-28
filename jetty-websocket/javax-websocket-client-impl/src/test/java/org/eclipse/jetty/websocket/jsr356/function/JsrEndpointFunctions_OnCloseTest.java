//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.function;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.test.DummyConnection;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.endpoints.TrackingSocket;
import org.junit.BeforeClass;
import org.junit.Test;

public class JsrEndpointFunctions_OnCloseTest
{
    private static final String EXPECTED_REASON = "CloseReason[1000,Normal]";
    private static ClientContainer container;

    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }
    
    private AvailableEncoders encoders;
    private AvailableDecoders decoders;
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig;
    
    public JsrEndpointFunctions_OnCloseTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }

    public JsrSession newSession(Object websocket)
    {
        String id = JsrEndpointFunctions_OnCloseTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        DummyConnection connection = new DummyConnection(policy);
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(websocket, config);
        return new JsrSession(container, id, requestURI, ei, connection);
    }

    private void assertOnCloseInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws Exception
    {
        JsrEndpointFunctions endpointFunctions = new JsrEndpointFunctions(
                socket, container.getPolicy(),
                container.getExecutor(),
                encoders,
                decoders,
                uriParams,
                endpointConfig
        );
        endpointFunctions.start();

        // These invocations are the same for all tests
        endpointFunctions.onOpen(newSession(socket));
        CloseInfo closeInfo = new CloseInfo(StatusCode.NORMAL, "Normal");
        endpointFunctions.onClose(closeInfo);
        socket.assertEvent(String.format(expectedEventFormat, args));
    }

    @ClientEndpoint
    public static class CloseSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose()
        {
            addEvent("OnClose()");
        }
    }

    @Test
    public void testInvokeClose() throws Exception
    {
        assertOnCloseInvocation(new CloseSocket(), "OnClose()");
    }

    @ClientEndpoint
    public static class CloseSessionSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(Session session)
        {
            addEvent("OnClose(%s)", session);
        }
    }

    @Test
    public void testInvokeCloseSession() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionSocket(),
                "OnClose(JsrSession[CLIENT,%s,DummyConnection])",
                CloseSessionSocket.class.getName());
    }

    @ClientEndpoint
    public static class CloseReasonSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(CloseReason reason)
        {
            addEvent("OnClose(%s)", reason);
        }
    }

    @Test
    public void testInvokeCloseReason() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSocket(),
                "OnClose(%s)", EXPECTED_REASON);
    }

    @ClientEndpoint
    public static class CloseSessionReasonSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(Session session, CloseReason reason)
        {
            addEvent("OnClose(%s, %s)", session, reason);
        }
    }

    @Test
    public void testInvokeCloseSessionReason() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionReasonSocket(),
                "OnClose(JsrSession[CLIENT,%s,DummyConnection], %s)",
                CloseSessionReasonSocket.class.getName(), EXPECTED_REASON);
    }

    @ClientEndpoint
    public static class CloseReasonSessionSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(CloseReason reason, Session session)
        {
            addEvent("OnClose(%s, %s)", reason, session);
        }
    }

    @Test
    public void testInvokeCloseReasonSession() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSessionSocket(),
                "OnClose(%s, JsrSession[CLIENT,%s,DummyConnection])",
                EXPECTED_REASON, CloseReasonSessionSocket.class.getName());
    }
}
