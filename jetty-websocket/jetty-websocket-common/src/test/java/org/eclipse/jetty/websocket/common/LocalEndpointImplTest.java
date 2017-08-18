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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.listeners.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketPartialListener;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionFactory;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.WSRemoteImpl;
import org.eclipse.jetty.websocket.core.util.EventQueue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class LocalEndpointImplTest
{
    @Rule
    public TestName testname = new TestName();

    private static Executor executor = Executors.newFixedThreadPool(10);
    private static ByteBufferPool bufferPool = new MappedByteBufferPool();
    private static DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
    private static WSExtensionFactory extensionFactory = new WSExtensionFactory();
    private LocalEndpointFactory endpointFactory = new LocalEndpointFactory();
    private WSPolicy policy = WSPolicy.newServerPolicy();

    private WSLocalEndpoint newLocalEndpoint(Object wsEndpoint)
    {
        EndPoint jettyEndpoint = new ByteArrayEndPoint();
        ExtensionStack extensionStack = new ExtensionStack(extensionFactory);

        UpgradeRequest upgradeRequest = null;
        UpgradeResponse upgradeResponse = null;

        WSSession session = new WSSession(jettyEndpoint, executor, bufferPool, objectFactory,
                policy, extensionStack, upgradeRequest, upgradeResponse);

        WSLocalEndpoint localEndpoint = endpointFactory.createLocalEndpoint(wsEndpoint, session, policy, executor);
        session.setWebSocketEndpoint(wsEndpoint, localEndpoint, new WSRemoteImpl(extensionStack));

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
    public void testConnectionOnly_OpenTextClose() throws Exception
    {
        ConnectionOnly socket = new ConnectionOnly();
        WSLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello?").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL, "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketClose\\([^\\)]*\\)");
    }

    public static class DataConnection extends ConnectionOnly implements WebSocketListener
    {
        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
            events.add("onWebSocketBinary(byte[%d], %d, %d)", payload.length, offset, len);
        }

        @Override
        public void onWebSocketText(String message)
        {
            events.add("onWebSocketText(%s)", message);
        }
    }

    @Test
    public void testWebSocketListener_OpenTextClose() throws Exception
    {
        // Setup
        DataConnection socket = new DataConnection();
        WSLocalEndpoint localEndpoint = newLocalEndpoint(socket);
        
        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello Text").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL, "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketText\\(Hello Text\\)",
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
        WSLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello Text Stream").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL, "Normal"));

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
        WSLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hel").setFin(false), Callback.NOOP);
        localEndpoint.onText(new ContinuationFrame().setPayload("lo ").setFin(false), Callback.NOOP);
        localEndpoint.onText(new ContinuationFrame().setPayload("Wor").setFin(false), Callback.NOOP);
        localEndpoint.onText(new ContinuationFrame().setPayload("ld").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL, "Normal"));

        // Await completion (of threads)
        socket.streamLatch.await(2, TimeUnit.SECONDS);

        // Validate Events
        socket.events.assertEvents("onTextStream\\(Hello World\\)");
    }

    public static class PartialData extends ConnectionOnly implements WebSocketPartialListener
    {
        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            events.add("onWebSocketPartialBinary(%s, %b)", BufferUtil.toDetailString(payload), fin);
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            events.add("onWebSocketPartialText(%s, %b)", payload, fin);
        }
    }

    @Test
    public void testWebSocketPartialListener() throws Exception
    {
        // Setup
        PartialData socket = new PartialData();
        WSLocalEndpoint localEndpoint = newLocalEndpoint(socket);

        // Trigger Events
        localEndpoint.onOpen();
        localEndpoint.onText(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onText(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onText(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onClose(new CloseStatus(StatusCode.NORMAL, "Normal"));

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketPartialText\\(Hello, false\\)",
                "onWebSocketPartialText\\( , false\\)",
                "onWebSocketPartialText\\(World, true\\)",
                "onWebSocketClose\\([^\\)]*\\)"
        );
    }

}
