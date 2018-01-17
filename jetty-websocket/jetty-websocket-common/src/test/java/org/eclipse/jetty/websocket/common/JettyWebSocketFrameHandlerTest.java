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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
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
import org.eclipse.jetty.websocket.common.handshake.DummyUpgradeRequest;
import org.eclipse.jetty.websocket.common.handshake.DummyUpgradeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.util.EventQueue;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class JettyWebSocketFrameHandlerTest
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
    private JettyWebSocketFrameHandlerFactory endpointFactory = new JettyWebSocketFrameHandlerFactory(executor);
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
    private FrameHandler.Channel channel = new DummyChannel();

    private JettyWebSocketFrameHandler newLocalFrameHandler(Object wsEndpoint)
    {
        UpgradeRequest upgradeRequest = new DummyUpgradeRequest();
        UpgradeResponse upgradeResponse = new DummyUpgradeResponse();
        JettyWebSocketFrameHandler localEndpoint = endpointFactory.createLocalEndpoint(wsEndpoint, policy, upgradeRequest, upgradeResponse);
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
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello?").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

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
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello Text Stream").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

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
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hel").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("lo ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Wor").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("ld").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

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
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()), Callback.NOOP);

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
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketText\\(\"Hello World\"\\)",
                "onWebSocketBinary\\(\\[12\\], 0, 12\\)",
                "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }

    @Test
    public void testListenerBasicSocket_Error() throws Exception
    {
        // Setup
        ListenerBasicSocket socket = new ListenerBasicSocket();
        JettyWebSocketFrameHandler localEndpoint = newLocalFrameHandler(socket);

        // Trigger Events
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onError(new RuntimeException("Nothing to see here"));

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
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

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
        localEndpoint.onOpen(channel);
        localEndpoint.onFrame(new TextFrame().setPayload("Hello").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new PingFrame().setPayload("You there?"), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("World").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new BinaryFrame().setPayload("Save").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload(" the ").setFin(false), Callback.NOOP);
        localEndpoint.onFrame(new PongFrame().setPayload("You there?"), Callback.NOOP);
        localEndpoint.onFrame(new ContinuationFrame().setPayload("Pig").setFin(true), Callback.NOOP);
        localEndpoint.onFrame(new CloseFrame().setPayload(StatusCode.NORMAL.getCode(), "Normal"), Callback.NOOP);

        // Validate Events
        socket.events.assertEvents(
                "onWebSocketConnect\\([^\\)]*\\)",
                "onWebSocketPing\\(.*ByteBuffer.*You there.*\\)",
                "onWebSocketPong\\(.*ByteBuffer.*You there.*\\)",
                "onWebSocketClose\\(NORMAL, \"Normal\"\\)"
        );
    }
}
