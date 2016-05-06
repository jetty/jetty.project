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

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
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

public class JsrEndpointFunctions_OnMessage_BinaryTest
{
    private static ClientContainer container;

    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }

    private AvailableEncoders encoders = new AvailableEncoders();
    private AvailableDecoders decoders = new AvailableDecoders();
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig = new EmptyClientEndpointConfig();

    private String expectedBuffer;

    public JsrSession newSession(Object websocket)
    {
        String id = JsrEndpointFunctions_OnMessage_BinaryTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        DummyConnection connection = new DummyConnection(policy);
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(websocket, config);
        return new JsrSession(container, id, requestURI, ei, connection);
    }

    private void assertOnMessageInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws InvocationTargetException, IllegalAccessException
    {
        JsrEndpointFunctions endpointFunctions = new JsrEndpointFunctions(
                socket, container.getPolicy(),
                container.getExecutor(),
                encoders,
                decoders,
                uriParams,
                endpointConfig
        );

        // This invocation is the same for all tests
        ByteBuffer byteBuffer = ByteBuffer.wrap("Hello World".getBytes(StandardCharsets.UTF_8));
        expectedBuffer = BufferUtil.toDetailString(byteBuffer);
        endpointFunctions.onBinary(byteBuffer, true);
        socket.assertEvent(String.format(expectedEventFormat, args));
    }

    public static class MessageSocket extends TrackingSocket
    {
        // TODO: Ambiguous declaration
        @OnMessage
        public void onMessage()
        {
            addEvent("onMessage()");
        }
    }

    @Test
    public void testInvokeMessage() throws InvocationTargetException, IllegalAccessException
    {
        assertOnMessageInvocation(new MessageSocket(), "onMessage()");
    }

    public static class MessageTextSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(String msg)
        {
            addEvent("onMessage(%s)", msg);
        }
    }

    @Test
    public void testInvokeMessageText() throws InvocationTargetException, IllegalAccessException
    {
        assertOnMessageInvocation(new MessageTextSocket(), "onMessage(Hello World)");
    }

    public static class MessageSessionSocket extends TrackingSocket
    {
        // TODO: Ambiguous declaration
        @OnMessage
        public void onMessage(Session session)
        {
            addEvent("onMessage(%s)", session);
        }
    }

    @Test
    public void testInvokeMessageSession() throws InvocationTargetException, IllegalAccessException
    {
        assertOnMessageInvocation(new MessageSessionSocket(),
                "onMessage(JsrSession[CLIENT,%s,DummyConnection])",
                MessageSessionSocket.class.getName());
    }

    public static class MessageSessionTextSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(Session session, String msg)
        {

            addEvent("onMessage(%s, %s)", session, msg);
        }
    }

    @Test
    public void testInvokeMessageSessionText() throws InvocationTargetException, IllegalAccessException
    {
        assertOnMessageInvocation(new MessageSessionTextSocket(),
                "onMessage(JsrSession[CLIENT,%s,DummyConnection], Hello World)",
                MessageSessionTextSocket.class.getName());
    }
}
