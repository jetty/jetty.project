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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.TrackingSocket;
import org.junit.Test;

public class JsrEndpointFunctions_OnMessage_BinaryTest extends AbstractJsrEndpointFunctionsTest
{
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
        endpointFunctions.onBinary(new BinaryFrame().setPayload(byteBuffer).setFin(true), new FrameCallback.Adapter());
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, is(String.format(expectedEventFormat, args)));
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
