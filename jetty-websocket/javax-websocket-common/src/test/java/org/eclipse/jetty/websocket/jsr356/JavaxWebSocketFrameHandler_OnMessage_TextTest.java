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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.eclipse.jetty.websocket.jsr356.util.InvalidSignatureException;
import org.hamcrest.Matcher;
import org.junit.Test;

public class JavaxWebSocketFrameHandler_OnMessage_TextTest extends AbstractJavaxWebSocketLocalEndpointTest
{
    private void onText(TrackingSocket socket, String msg) throws Exception
    {
        JavaxWebSocketFrameHandler localEndpoint = newJavaxFrameHandler(socket);

        // This invocation is the same for all tests
        localEndpoint.onOpen(channel);
        
        ByteBuffer payload = BufferUtil.toBuffer(msg, StandardCharsets.UTF_8);
        localEndpoint.onFrame(new TextFrame().setPayload(payload).setFin(true), Callback.NOOP);
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
                allOf(
                    containsString("onMessage(JavaxWebSocketSession@"),
                    containsString(MessageSessionTextSocket.class.getName()),
                    containsString(", Hello World)")
                ));
    }
}
