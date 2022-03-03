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

package org.eclipse.jetty.ee9.websocket.common;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketFrameHandlerTest
{
    private static DummyContainer container;

    private final WebSocketComponents components;
    private final JettyWebSocketFrameHandlerFactory endpointFactory;
    private final CoreSession coreSession;

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

    public JettyWebSocketFrameHandlerTest()
    {
        components = new WebSocketComponents();
        endpointFactory = new JettyWebSocketFrameHandlerFactory(container, components);
        coreSession = new CoreSession.Empty()
        {
            @Override
            public Behavior getBehavior()
            {
                return Behavior.CLIENT;
            }

            @Override
            public WebSocketComponents getWebSocketComponents()
            {
                return components;
            }
        };

        LifeCycle.start(components);
    }

    private JettyWebSocketFrameHandler newLocalFrameHandler(Object wsEndpoint)
    {
        return endpointFactory.newJettyFrameHandler(wsEndpoint);
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
    public void testConnectionListener()
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
    public void testAnnotatedStreamedTextSingle()
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
    public void testAnnotatedStreamedTextMultipleParts()
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
            assertTrue(socket.streamLatch.await(2, TimeUnit.SECONDS));

            // Validate Events
            socket.events.assertEvents("onTextStream\\(Hello World\\)");
        });
    }

    @Test
    public void testListenerPartialSocket()
    {
        // Setup
        EndPoints.ListenerPartialSocket socket = new EndPoints.ListenerPartialSocket();
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
    public void testListenerBasicSocket()
    {
        // Setup
        EndPoints.ListenerBasicSocket socket = new EndPoints.ListenerBasicSocket();
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
    public void testListenerBasicSocketError()
    {
        // Setup
        EndPoints.ListenerBasicSocket socket = new EndPoints.ListenerBasicSocket();
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
    public void testListenerFrameSocket()
    {
        // Setup
        EndPoints.ListenerFrameSocket socket = new EndPoints.ListenerFrameSocket();
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
    public void testListenerPingPongSocket()
    {
        // Setup
        EndPoints.ListenerPingPongSocket socket = new EndPoints.ListenerPingPongSocket();
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
