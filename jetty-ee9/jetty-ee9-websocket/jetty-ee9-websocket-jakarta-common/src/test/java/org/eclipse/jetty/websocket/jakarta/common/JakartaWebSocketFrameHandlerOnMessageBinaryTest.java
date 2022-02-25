//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.jakarta.common.sockets.TrackingSocket;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JakartaWebSocketFrameHandlerOnMessageBinaryTest extends AbstractJakartaWebSocketFrameHandlerTest
{
    private void assertOnMessageInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        JakartaWebSocketFrameHandler localEndpoint = newJakartaFrameHandler(socket);

        // This invocation is the same for all tests
        localEndpoint.onOpen(coreSession, Callback.NOOP);

        assertThat("Has Binary Metadata", localEndpoint.getBinaryMetadata(), notNullValue());

        // This invocation is the same for all tests
        ByteBuffer byteBuffer = ByteBuffer.wrap("Hello World".getBytes(StandardCharsets.UTF_8));
        localEndpoint.onFrame(new Frame(OpCode.BINARY).setPayload(byteBuffer).setFin(true), Callback.NOOP);
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, eventMatcher);
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
        assertThrows(InvalidSignatureException.class, () ->
            assertOnMessageInvocation(new MessageSocket(), containsString("onMessage()"))
        );
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
        assertOnMessageInvocation(new MessageByteBufferSocket(), containsString("onMessage(Hello World)"));
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
        assertThrows(InvalidSignatureException.class, () ->
            assertOnMessageInvocation(new MessageSessionSocket(),
                allOf(
                    containsString("onMessage(JakartaWebSocketSession@"),
                    containsString(MessageSessionSocket.class.getName())
                ))
        );
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
            allOf(
                containsString("onMessage(JakartaWebSocketSession@"),
                containsString(MessageSessionByteBufferSocket.class.getName())
            ));
    }
}
