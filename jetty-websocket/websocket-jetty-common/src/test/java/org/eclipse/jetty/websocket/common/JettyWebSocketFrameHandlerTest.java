//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerBasicSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerFrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerPartialSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerPingPongSocket;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class JettyWebSocketFrameHandlerTest
{
    private static DummyContainer container;

    @BeforeAll
    public static void startContainer() throws Exception
    {
        container = new DummyContainer();
        container.start();
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }

    private JettyWebSocketFrameHandlerFactory endpointFactory = new JettyWebSocketFrameHandlerFactory(container);
    private FrameHandler.CoreSession coreSession = new FrameHandler.CoreSession.Empty()
    {
        @Override
        public Behavior getBehavior()
        {
            return Behavior.CLIENT;
        }
    };

    private JettyWebSocketFrameHandler newLocalFrameHandler(Object wsEndpoint)
    {
        JettyWebSocketFrameHandler localEndpoint = endpointFactory.newJettyFrameHandler(wsEndpoint);
        return localEndpoint;
    }

    public static class ConnectionOnly implements WebSocketConnectionListener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%d, %s)", statusCode, reason);
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            events.add("onWebSocketConnect(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError(%s)", cause);
        }
    }

    @Test
    public void testConnectionListener() throws Exception
    {
        ConnectionOnly socket = new ConnectionOnly();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello?").setFin(true), Callback.NOOP);
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketClose\\([^\\)]*\\)");
    }

    @WebSocket
    public static class StreamedText
    {
        public final CountDownLatch streamLatch;
        public EventQueue events = new EventQueue();

        public StreamedText(int expectedStreams)
        {
            this.streamLatch = new CountDownLatch(expectedStreams);
        }

        @SuppressWarnings("unused")
        @OnWebSocketMessage
        public void onTextStream(Reader reader) throws IOException
        {
            assertThat("Reader", reader, notNullValue());

            StringWriter out = new StringWriter();
            IO.copy(reader, out);
            events.add("onTextStream(%s)", out.toString());
            streamLatch.countDown();
        }
    }

    @Test
    public void testAnnotatedStreamedTextSingle() throws Exception
    {
        assertTimeout(Duration.ofMillis(1000), () ->
        {
            // Setup
            StreamedText socket = new StreamedText(1);
            JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

            // Trigger Events
            localEndpoint.onOpen(coreSession, Callback.NOOP);
            localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello Text Stream").setFin(true), Callback.NOOP);
            localEndpoint.onFrame(CloseStatus.toFrame(StatusCode.NORMAL, "Normal"), Callback.NOOP);

            // Await completion (of threads)
            socket.streamLatch.await(2, TimeUnit.SECONDS);

            // Validate Events
            socket.events.assertEvents("onTextStream\\(Hello Text Stream\\)");
        });
    }

    @Test
    public void testAnnotatedStreamedTextMultipleParts() throws Exception
    {
        assertTimeout(Duration.ofMillis(1000), () ->
        {
            // Setup
            StreamedText socket = new StreamedText(1);
            JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

            // Trigger Events
            localEndpoint.onOpen(coreSession, Callback.NOOP);
            localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hel").setFin(false), Callback.NOOP);
            localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("lo ").setFin(false), Callback.NOOP);
            localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("Wor").setFin(false), Callback.NOOP);
            localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("ld").setFin(true), Callback.NOOP);
            localEndpoint.onFrame(CloseStatus.toFrame(StatusCode.NORMAL, "Normal"), Callback.NOOP);

            // Await completion (of threads)
            socket.streamLatch.await(2, TimeUnit.SECONDS);

            // Validate Events
            socket.events.assertEvents("onTextStream\\(Hello World\\)");
        });
    }

    @Test
    public void testListenerPartialSocket() throws Exception
    {
        // Setup
        ListenerPartialSocket socket = new ListenerPartialSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.BINARY).setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(CloseStatus.toFrame(StatusCode.NORMAL), Callback.NOOP);
        localEndpoint.onClosed(CloseStatus.NORMAL_STATUS, Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketPartialText\\(\"Hello\", false\\)",
            "onWebSocketPartialText\\(\" \", false\\)",
            "onWebSocketPartialText\\(\"World\", true\\)",
            "onWebSocketPartialBinary\\(.*ByteBuffer.*Save.*, false\\)",
            "onWebSocketPartialBinary\\(.*ByteBuffer.* the .*, false\\)",
            "onWebSocketPartialBinary\\(.*ByteBuffer.*Pig.*, true\\)",
            "onWebSocketClose\\(NORMAL, <null>\\)"
        );
    }

    @Test
    public void testListenerBasicSocket() throws Exception
    {
        // Setup
        ListenerBasicSocket socket = new ListenerBasicSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.BINARY).setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketText\\(\"Hello World\"\\)",
            "onWebSocketBinary\\(\\[12\\], 0, 12\\)",
            "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }

    @Test
    public void testListenerBasicSocketError() throws Exception
    {
        // Setup
        ListenerBasicSocket socket = new ListenerBasicSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onError(new RuntimeException("Nothing to see here"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketError\\(\\(RuntimeException\\) \"Nothing to see here\"\\)"
        );
    }

    @Test
    public void testListenerFrameSocket() throws Exception
    {
        // Setup
        ListenerFrameSocket socket = new ListenerFrameSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.BINARY).setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(CloseStatus.toFrame(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketFrame\\(.*TEXT@[0-9a-f]*.len=5,fin=false,.*\\)",
            "onWebSocketFrame\\(.*CONTINUATION@[0-9a-f]*.len=1,fin=false,.*\\)",
            "onWebSocketFrame\\(.*CONTINUATION@[0-9a-f]*.len=5,fin=true,.*\\)",
            "onWebSocketFrame\\(.*BINARY@[0-9a-f]*.len=4,fin=false,.*\\)",
            "onWebSocketFrame\\(.*CONTINUATION@[0-9a-f]*.len=5,fin=false,.*\\)",
            "onWebSocketFrame\\(.*CONTINUATION@[0-9a-f]*.len=3,fin=true,.*\\)",
            "onWebSocketFrame\\(.*CLOSE@[0-9a-f]*.len=8,fin=true,.*\\)"
        );
    }

    @Test
    public void testListenerPingPongSocket() throws Exception
    {
        // Setup
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.PING).setPayload("You there?"), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.BINARY).setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.PONG).setPayload("You there?"), Callback.NOOP);
        localEndpoint.onFrame(new Frame(OpCode.CONTINUATION).setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketConnect\\([^\\)]*\\)",
            "onWebSocketPing\\(.*ByteBuffer.*You there.*\\)",
            "onWebSocketPong\\(.*ByteBuffer.*You there.*\\)",
            "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }
}
