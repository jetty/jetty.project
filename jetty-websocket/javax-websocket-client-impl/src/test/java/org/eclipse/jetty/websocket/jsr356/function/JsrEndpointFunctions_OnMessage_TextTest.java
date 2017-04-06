//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.DummyConnection;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.endpoints.TrackingSocket;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsrEndpointFunctions_OnMessage_TextTest
{
    private static ClientContainer container;

    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private AvailableEncoders encoders;
    private AvailableDecoders decoders;
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig;
    
    public JsrEndpointFunctions_OnMessage_TextTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }
    
    public JsrSession newSession(Object websocket)
    {
        String id = JsrEndpointFunctions_OnMessage_TextTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        DummyConnection connection = new DummyConnection(policy);
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(websocket, config);
        return new JsrSession(container, id, requestURI, ei, connection);
    }
    
    private void onText(TrackingSocket socket, String msg) throws Exception
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
    
        // This invocation is the same for all tests
        endpointFunctions.onOpen(newSession(socket));
        
        ByteBuffer payload = BufferUtil.toBuffer(msg, StandardCharsets.UTF_8);
        endpointFunctions.onText(new TextFrame().setPayload(payload).setFin(true), new FrameCallback.Adapter());
    }

    private void assertOnMessageInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws Exception
    {
        onText(socket, "Hello World");
        socket.assertEvent(String.format(expectedEventFormat, args));
    }
    
    @ClientEndpoint
    public static class MessageSocket extends TrackingSocket
    {
        /**
         * Invalid declaration - the type is ambiguous (is it TEXT / BINARY / PONG?)
         */
        @SuppressWarnings("IncorrectOnMessageMethodsInspection")
        @OnMessage
        public void onMessage()
        {
            addEvent("onMessage()");
        }
    }

    @Test
    public void testAmbiguousEmptyMessage() throws Exception
    {
        MessageSocket socket = new MessageSocket();
        expectedException.expect(InvalidSignatureException.class);
        expectedException.expectMessage(containsString("@OnMessage public void onMessage"));
        onText(socket, "Hello World");
    }
    
    @ClientEndpoint
    public static class MessageTextSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(String msg)
        {
            addEvent("onMessage(%s)", msg);
        }
    }

    @Test
    public void testInvokeMessageText() throws Exception
    {
        assertOnMessageInvocation(new MessageTextSocket(), "onMessage(Hello World)");
    }

    @ClientEndpoint
    public static class MessageSessionSocket extends TrackingSocket
    {
        /**
         * Invalid declaration - the type is ambiguous (is it TEXT, BINARY, or PONG?)
         */
        @OnMessage
        public void onMessage(Session session)
        {
            addEvent("onMessage(%s)", session);
        }
    }

    @Test
    public void testAmbiguousMessageSession() throws Exception
    {
        MessageSessionSocket socket = new MessageSessionSocket();

        expectedException.expect(InvalidSignatureException.class);
        expectedException.expectMessage(containsString("@OnMessage public void onMessage"));
        onText(socket, "Hello World");
    }
    
    @ClientEndpoint
    public static class MessageSessionTextSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(Session session, String msg)
        {
            addEvent("onMessage(%s, %s)", session, msg);
        }
    }

    @Test
    public void testInvokeMessageSessionText() throws Exception
    {
        assertOnMessageInvocation(new MessageSessionTextSocket(),
                "onMessage(JsrSession[CLIENT,%s,DummyConnection], Hello World)",
                MessageSessionTextSocket.class.getName());
    }
}
