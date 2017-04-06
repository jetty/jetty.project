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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

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
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
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

public class JsrEndpointFunctions_OnMessage_BinaryTest
{
    private static ClientContainer container;
    
    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private String expectedBuffer;
    private AvailableEncoders encoders;
    private AvailableDecoders decoders;
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig;
    
    public JsrEndpointFunctions_OnMessage_BinaryTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }
    
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
    
    private void assertOnMessageInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws Exception
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
        
        assertThat("Has BinarySink", endpointFunctions.hasBinarySink(), is(true));
        
        // This invocation is the same for all tests
        ByteBuffer byteBuffer = ByteBuffer.wrap("Hello World".getBytes(StandardCharsets.UTF_8));
        expectedBuffer = BufferUtil.toDetailString(byteBuffer);
        endpointFunctions.onBinary(new BinaryFrame().setPayload(byteBuffer).setFin(true), new FrameCallback.Adapter());
        socket.assertEvent(String.format(expectedEventFormat, args));
    }
    
    @ClientEndpoint
    public static class MessageSocket extends TrackingSocket
    {
        // Invalid OnMessage - mandatory type (TEXT/BINARY) missing
        @SuppressWarnings("IncorrectOnMessageMethodsInspection")
        @OnMessage
        public void onMessage()
        {
            addEvent("onMessage()");
        }
    }
    
    @Test
    public void testInvokeMessage() throws Exception
    {
        expectedException.expect(InvalidSignatureException.class);
        assertOnMessageInvocation(new MessageSocket(), "onMessage()");
    }
    
    @ClientEndpoint
    public static class MessageByteBufferSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(ByteBuffer msg)
        {
            addEvent("onMessage(%s)", BufferUtil.toUTF8String(msg));
        }
    }
    
    @Test
    public void testInvokeMessageByteBuffer() throws Exception
    {
        assertOnMessageInvocation(new MessageByteBufferSocket(), "onMessage(Hello World)");
    }
    
    @ClientEndpoint
    public static class MessageSessionSocket extends TrackingSocket
    {
        // Invalid OnMessage - mandatory type (TEXT/BINARY) missing
        @OnMessage
        public void onMessage(Session session)
        {
            addEvent("onMessage(%s)", session);
        }
    }
    
    @Test
    public void testInvokeMessageSession() throws Exception
    {
        expectedException.expect(InvalidSignatureException.class);
        assertOnMessageInvocation(new MessageSessionSocket(),
                "onMessage(JsrSession[CLIENT,%s,DummyConnection])",
                MessageSessionSocket.class.getName());
    }
    
    @ClientEndpoint
    public static class MessageSessionByteBufferSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(Session session, ByteBuffer msg)
        {
            addEvent("onMessage(%s, %s)", session, BufferUtil.toUTF8String(msg));
        }
    }
    
    @Test
    public void testInvokeMessageSessionByteBuffer() throws Exception
    {
        assertOnMessageInvocation(new MessageSessionByteBufferSocket(),
                "onMessage(JsrSession[CLIENT,%s,DummyConnection], Hello World)",
                MessageSessionByteBufferSocket.class.getName());
    }
}
