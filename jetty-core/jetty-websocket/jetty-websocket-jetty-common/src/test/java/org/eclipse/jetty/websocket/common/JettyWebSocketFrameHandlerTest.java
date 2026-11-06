//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
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
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
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

    public static class ConnectionOnly implements Session.Listener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%d, %s)", statusCode, reason);
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            events.add("onWebSocketOpen(%s)", session);
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
        try (Frame frame = new Frame(OpCode.TEXT, "Hello?"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
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
            try (Frame frame = new Frame(OpCode.TEXT, "Hello Text Stream"))
            {
                localEndpoint.onFrame(frame, Callback.NOOP);
                try (Frame close = CloseStatus.toFrame(StatusCode.NORMAL, "Normal"))
                {
                    localEndpoint.onFrame(close, Callback.NOOP);
                }

                // Await completion (of threads)
                assertTrue(socket.streamLatch.await(2, TimeUnit.SECONDS));
                // Validate Events
                socket.events.assertEvents("onTextStream\\(Hello Text Stream\\)");
            }
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
            try (Frame frame1 = new Frame(OpCode.TEXT, false, "Hel");
                 Frame frame2 = new Frame(OpCode.CONTINUATION, false, "lo ");
                 Frame frame3 = new Frame(OpCode.CONTINUATION, false, "Wor");
                 Frame frame4 = new Frame(OpCode.CONTINUATION, "ld");
                 Frame close = CloseStatus.toFrame(StatusCode.NORMAL, "Normal"))
            {
                localEndpoint.onFrame(frame1, Callback.NOOP);
                localEndpoint.onFrame(frame2, Callback.NOOP);
                localEndpoint.onFrame(frame3, Callback.NOOP);
                localEndpoint.onFrame(frame4, Callback.NOOP);
                localEndpoint.onFrame(close, Callback.NOOP);

                // Await completion (of threads)
                assertTrue(socket.streamLatch.await(2, TimeUnit.SECONDS));
                // Validate Events
                socket.events.assertEvents("onTextStream\\(Hello World\\)");
            }
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
        try (Frame frame = new Frame(OpCode.TEXT, false, "Hello"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "World"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.BINARY, false, "Save"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " the "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "Pig"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = CloseStatus.toFrame(StatusCode.NORMAL))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        localEndpoint.onClosed(CloseStatus.NORMAL_STATUS, Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
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
        try (Frame frame = new Frame(OpCode.TEXT, false, "Hello"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "World"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.BINARY, false, "Save"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " the "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "Pig"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
            "onWebSocketText\\(\"Hello World\"\\)",
            "onWebSocketBinary\\(\\[12\\]\\)",
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
        try (Frame frame = new Frame(OpCode.TEXT, false, "Hello"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        localEndpoint.onError(new RuntimeException("Nothing to see here"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
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
        try (Frame frame = new Frame(OpCode.TEXT, false, "Hello"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "World"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.BINARY, false, "Save"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " the "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "Pig"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = CloseStatus.toFrame(StatusCode.NORMAL, "Normal"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
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
        try (Frame frame = new Frame(OpCode.TEXT, false, "Hello"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.PING, "You there?"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "World"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.BINARY, false, "Save"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, " the "))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.PONG, "You there?"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, "Pig"))
        {
            localEndpoint.onFrame(frame, Callback.NOOP);
        }
        localEndpoint.onClosed(new CloseStatus(StatusCode.NORMAL, "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
            "onWebSocketOpen\\([^\\)]*\\)",
            "onWebSocketPing\\(.*ByteBuffer.*You there.*\\)",
            "onWebSocketPong\\(.*ByteBuffer.*You there.*\\)",
            "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }
}
