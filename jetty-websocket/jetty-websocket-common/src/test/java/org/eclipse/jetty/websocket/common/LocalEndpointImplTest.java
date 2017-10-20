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

package org.eclipse.jetty.websocket.common;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.listeners.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerBasicSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerFrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerPartialSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerPingPongSocket;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.util.EventQueue;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class LocalEndpointImplTest
{
    @Rule
    public TestName testname = new TestName();

    public static DummyContainer container;

    @BeforeClass
    public static void startContainer() throws Exception
    {
        container = new DummyContainer();
        container.start();
    }

    @AfterClass
    public static void stopContainer() throws Exception
    {
        container.stop();
    }

    private static Executor executor = Executors.newFixedThreadPool(10);
    private static ByteBufferPool bufferPool = new MappedByteBufferPool();
    private static DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
    private static WebSocketExtensionRegistry extensionFactory = new WebSocketExtensionRegistry();
    private LocalEndpointFactory endpointFactory = new LocalEndpointFactory();
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

    private WebSocketLocalEndpoint newLocalEndpoint(Object wsEndpoint)
    {
        EndPoint jettyEndpoint = new ByteArrayEndPoint();
        ExtensionStack extensionStack = new ExtensionStack(extensionFactory);
        List<ExtensionConfig> configs = new ArrayList<>();
        extensionStack.negotiate(objectFactory, policy, bufferPool, configs);

        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        UpgradeResponse upgradeResponse = new UpgradeResponseAdapter();



        LocalEndpointImpl localEndpoint = endpointFactory.createLocalEndpoint(wsEndpoint, session, policy, executor);
        RemoteEndpointImpl remoteEndpoint =new RemoteEndpointImpl(extensionStack, connection.getRemoteAddress());

        WebSocketSessionImpl session = new WebSocketSessionImpl(localEndpoint, remoteEndpoint, policy, extensionStack, upgradeRequest, upgradeResponse);
        WebSocketCoreConnection connection = new WebSocketCoreConnection(jettyEndpoint, executor, bufferPool, session);

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
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello?").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode(), "Normal"));

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

    @Test(timeout = 1000)
    public void testAnnotatedStreamedText_Single() throws Exception
    {
        // Setup
        StreamedText socket = new StreamedText(1);
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello Text Stream").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode(), "Normal"));

        // Await completion (of threads)
        socket.streamLatch.await(2, TimeUnit.SECONDS);

        // Validate Events
        socket.events.assertEvents("onTextStream\\(Hello Text Stream\\)");
    }

    @Test(timeout = 1000)
    public void testAnnotatedStreamedText_MultipleParts() throws Exception
    {
        // Setup
        StreamedText socket = new StreamedText(1);
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hel").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("lo ").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("Wor").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("ld").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode(), "Normal"));

        // Await completion (of threads)
        socket.streamLatch.await(2, TimeUnit.SECONDS);

        // Validate Events
        socket.events.assertEvents("onTextStream\\(Hello World\\)");
    }

    @Test
    public void testListenerPartialSocket() throws Exception
    {
        // Setup
        ListenerPartialSocket socket = new ListenerPartialSocket();
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onBinary(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode()));

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
        ListenerBasicSocket socket = new ListenerBasicSocket();
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onBinary(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode(), "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketText\\(\"Hello World\"\\)",
                "onWebSocketBinary\\(\\[12\\], 0, 12\\)",
                "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }

    @Test
    public void testListenerBasicSocket_Error()
    {
        // Setup
        ListenerBasicSocket socket = new ListenerBasicSocket();
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onError(new RuntimeException("Nothing to see here"));

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
        ListenerFrameSocket socket = new ListenerFrameSocket();
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false));
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false));
        localEndpoint.onFrame(new ContinuationFrame().setPayload("World").setFin(true));
        localEndpoint.onFrame(new BinaryFrame().setPayload("Save").setFin(false));
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" the ").setFin(false));
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Pig").setFin(true));
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketFrame\\(.*TEXT.len=5,fin=false,.*\\)",
                "onWebSocketFrame\\(.*CONTINUATION.len=1,fin=false,.*\\)",
                "onWebSocketFrame\\(.*CONTINUATION.len=5,fin=true,.*\\)",
                "onWebSocketFrame\\(.*BINARY.len=4,fin=false,.*\\)",
                "onWebSocketFrame\\(.*CONTINUATION.len=5,fin=false,.*\\)",
                "onWebSocketFrame\\(.*CONTINUATION.len=3,fin=true,.*\\)",
                "onWebSocketFrame\\(.*CLOSE.len=8,fin=true,.*\\)"
        );
    }

    @Test
    public void testListenerPingPongSocket()
    {
        // Setup
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        WebSocketLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onPing(BufferUtil.toBuffer("You there?", UTF_8));
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onBinary(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onContinuation(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onPong(BufferUtil.toBuffer("You there?", UTF_8));
        localEndpoint.onContinuation(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL.getCode(), "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketPing\\(.*ByteBuffer.*You there.*\\)",
                "onWebSocketPong\\(.*ByteBuffer.*You there.*\\)",
                "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }
}
