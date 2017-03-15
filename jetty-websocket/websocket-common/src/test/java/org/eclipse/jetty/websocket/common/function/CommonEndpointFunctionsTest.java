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

package org.eclipse.jetty.websocket.common.function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.test.EventTracker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class CommonEndpointFunctionsTest
{
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private WebSocketContainerScope containerScope = new SimpleContainerScope(WebSocketPolicy.newServerPolicy());

    @Rule
    public TestName testname = new TestName();
    
    private class CloseableEndpointFunctions extends CommonEndpointFunctions implements AutoCloseable
    {
        public CloseableEndpointFunctions(Object endpoint, WebSocketContainerScope containerScope) throws Exception
        {
            super(endpoint, containerScope.getPolicy(), containerScope.getExecutor());
            start();
        }
    
        @Override
        public void close() throws Exception
        {
            stop();
        }
    }

    public Session initSession(Object websocket)
    {
        return new LocalWebSocketSession(containerScope, testname, websocket);
    }

    public static class ConnectionOnly extends EventTracker implements WebSocketConnectionListener
    {
        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            addEvent("onWebSocketClose(%d, %s)", statusCode, reason);
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            addEvent("onWebSocketConnect(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            addEvent("onWebSocketError(%s)", cause);
        }
    }

    @Test
    public void testWebSocketConnectionListener_OpenTextClose() throws Exception
    {
        // Setup
        ConnectionOnly socket = new ConnectionOnly();
        Session session = initSession(socket);
        try (CloseableEndpointFunctions endpointFunctions = new CloseableEndpointFunctions(socket, containerScope))
        {
            // Trigger Events
            endpointFunctions.onOpen(session);
            endpointFunctions.onText(BufferUtil.toBuffer("Hello?", UTF8), true);
            endpointFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "Normal"));
        }

        // Validate Events
        socket.assertCaptured(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketClose\\([^\\)]*\\)");
    }

    public static class DataConnection extends ConnectionOnly implements WebSocketListener
    {
        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
            addEvent("onWebSocketBinary(byte[%d], %d, %d)", payload.length, offset, len);
        }

        @Override
        public void onWebSocketText(String message)
        {
            addEvent("onWebSocketText(%s)", message);
        }
    }

    @Test
    public void testWebSocketListener_OpenTextClose() throws Exception
    {
        // Setup
        DataConnection socket = new DataConnection();
        Session session = initSession(socket);
        try (CloseableEndpointFunctions endpointFunctions = new CloseableEndpointFunctions(socket, containerScope))
        {
            // Trigger Events
            endpointFunctions.onOpen(session);
            endpointFunctions.onText(BufferUtil.toBuffer("Hello Text", UTF8), true);
            endpointFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "Normal"));
        }

        // Validate Events
        socket.assertCaptured(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketText\\(Hello Text\\)",
                "onWebSocketClose\\([^\\)]*\\)");
    }

    @WebSocket
    public static class StreamedText extends EventTracker
    {
        public final CountDownLatch streamLatch;

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
            addEvent("onTextStream(%s)", out.toString());
            streamLatch.countDown();
        }
    }

    @Test(timeout = 1000)
    public void testAnnotatedStreamedText_Single() throws Exception
    {
        // Setup
        StreamedText socket = new StreamedText(1);
        Session session = initSession(socket);
    
        try (CloseableEndpointFunctions endpointFunctions = new CloseableEndpointFunctions(socket, containerScope))
        {
            // Trigger Events
            endpointFunctions.onOpen(session);
            endpointFunctions.onText(BufferUtil.toBuffer("Hello Text Stream", UTF8), true);
            endpointFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "Normal"));
        }

        // Await completion (of threads)
        socket.streamLatch.await(2, TimeUnit.SECONDS);

        // Validate Events
        socket.assertCaptured("onTextStream\\(Hello Text Stream\\)");
    }

    @Test(timeout = 1000)
    public void testAnnotatedStreamedText_MultipleParts() throws Exception
    {
        // Setup
        StreamedText socket = new StreamedText(1);
        Session session = initSession(socket);
        try (CloseableEndpointFunctions endpointFunctions = new CloseableEndpointFunctions(socket, containerScope))
        {
            // Trigger Events
            endpointFunctions.onOpen(session);
            endpointFunctions.onText(BufferUtil.toBuffer("Hel"), false);
            endpointFunctions.onText(BufferUtil.toBuffer("lo "), false);
            endpointFunctions.onText(BufferUtil.toBuffer("Wor"), false);
            endpointFunctions.onText(BufferUtil.toBuffer("ld"), true);
            endpointFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "Normal"));
        }

        // Await completion (of threads)
        socket.streamLatch.await(2, TimeUnit.SECONDS);

        // Validate Events
        socket.assertCaptured("onTextStream\\(Hello World\\)");
    }

    public static class PartialData extends ConnectionOnly implements WebSocketPartialListener
    {
        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            addEvent("onWebSocketPartialBinary(%s, %b)", BufferUtil.toDetailString(payload), fin);
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            addEvent("onWebSocketPartialText(%s, %b)", payload, fin);
        }
    }

    @Test
    public void testWebSocketPartialListener() throws Exception
    {
        // Setup
        PartialData socket = new PartialData();
        Session session = initSession(socket);
        try (CloseableEndpointFunctions endpointFunctions = new CloseableEndpointFunctions(socket, containerScope))
        {
            // Trigger Events
            endpointFunctions.onOpen(session);
            endpointFunctions.onText(BufferUtil.toBuffer("Hello"), false);
            endpointFunctions.onText(BufferUtil.toBuffer(" "), false);
            endpointFunctions.onText(BufferUtil.toBuffer("World"), true);
            endpointFunctions.onClose(new CloseInfo(StatusCode.NORMAL, "Normal"));
        }

        // Validate Events
        socket.assertCaptured(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketPartialText\\(Hello, false\\)",
                "onWebSocketPartialText\\( , false\\)",
                "onWebSocketPartialText\\(World, true\\)",
                "onWebSocketClose\\([^\\)]*\\)"
        );
    }
}
