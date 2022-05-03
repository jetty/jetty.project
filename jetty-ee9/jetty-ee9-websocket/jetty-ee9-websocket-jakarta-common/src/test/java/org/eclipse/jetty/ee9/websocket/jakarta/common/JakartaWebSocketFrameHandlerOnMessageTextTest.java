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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.eclipse.jetty.ee9.websocket.jakarta.common.sockets.TrackingSocket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JakartaWebSocketFrameHandlerOnMessageTextTest extends AbstractJakartaWebSocketFrameHandlerTest
{
    private void onText(TrackingSocket socket, String msg) throws Exception
    {
        JakartaWebSocketFrameHandler localEndpoint = newJakartaFrameHandler(socket);

        // This invocation is the same for all tests
        localEndpoint.onOpen(coreSession, Callback.NOOP);

        ByteBuffer payload = BufferUtil.toBuffer(msg, StandardCharsets.UTF_8);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload(payload).setFin(true), Callback.NOOP);
    }

    private void assertOnMessageInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        onText(socket, "Hello World");
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, eventMatcher);
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
        Exception e = assertThrows(InvalidSignatureException.class, () -> onText(socket, "Hello World"));
        assertThat(e.getMessage(), containsString("@OnMessage public void " + MessageSocket.class.getName() + "#onMessage"));
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
        assertOnMessageInvocation(new MessageTextSocket(), containsString("onMessage(Hello World)"));
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
        Exception e = assertThrows(InvalidSignatureException.class, () -> onText(socket, "Hello World"));
        assertThat(e.getMessage(), containsString("@OnMessage public void " + MessageSessionSocket.class.getName() + "#onMessage"));
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
            allOf(
                containsString("onMessage(JakartaWebSocketSession@"),
                containsString(MessageSessionTextSocket.class.getName()),
                containsString(", Hello World)")
            ));
    }
}
