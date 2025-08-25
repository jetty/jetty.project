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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketPing;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketPong;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PingPongTest
{
    Server _server;
    ServerConnector _connector;
    WebSocketClient _client;
    AnnotatedPingPongSocket _annotatedPingPongSocket;
    ListenerPingPongSocket _listenerPingPongSocket;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _client = new WebSocketClient();

        WebSocketUpgradeHandler upgradeHandler = WebSocketUpgradeHandler.from(_server);
        _annotatedPingPongSocket = new AnnotatedPingPongSocket();
        _listenerPingPongSocket = new ListenerPingPongSocket();
        upgradeHandler.getServerWebSocketContainer().addMapping("/annotatedEndpoint", (req, resp, cb) -> _annotatedPingPongSocket);
        upgradeHandler.getServerWebSocketContainer().addMapping("/listenerEndpoint", (req, resp, cb) -> _listenerPingPongSocket);
        _server.setHandler(upgradeHandler);

        _server.start();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testAnnotatedPingPong() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/annotatedEndpoint");
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get();

        // Test sending a ping and receiving it on the server.
        session.sendPing(BufferUtil.toBuffer("ping from test"), Callback.NOOP);
        ByteBuffer pingPayload = _annotatedPingPongSocket.pings.poll(5, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(pingPayload), equalTo("ping from test"));

        // Test sending a pong and receiving it on the server.
        session.sendPong(BufferUtil.toBuffer("pong from test"), Callback.NOOP);
        ByteBuffer pongPayload = _annotatedPingPongSocket.pongs.poll(5, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(pongPayload), equalTo("pong from test"));

        // Close the session.
        session.close();
        assertTrue(_annotatedPingPongSocket.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        // Verify that no more pings or pongs were sent or received.
        assertTrue(_annotatedPingPongSocket.pings.isEmpty());
        assertTrue(_annotatedPingPongSocket.pongs.isEmpty());
        assertTrue(clientEndpoint.pongMessages.isEmpty());
        assertTrue(clientEndpoint.pingMessages.isEmpty());
    }

    @Test
    public void testListenerPingPong() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/listenerEndpoint");
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get();

        // Test sending a ping and receiving it on the server.
        session.sendPing(BufferUtil.toBuffer("ping from test"), Callback.NOOP);
        ByteBuffer pingPayload = _listenerPingPongSocket.pings.poll(5, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(pingPayload), equalTo("ping from test"));

        // Test sending a pong and receiving it on the server.
        session.sendPong(BufferUtil.toBuffer("pong from test"), Callback.NOOP);
        ByteBuffer pongPayload = _listenerPingPongSocket.pongs.poll(5, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(pongPayload), equalTo("pong from test"));

        // Close the session.
        session.close();
        assertTrue(_listenerPingPongSocket.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        // Verify that no more pings or pongs were sent or received.
        assertTrue(_listenerPingPongSocket.pings.isEmpty());
        assertTrue(_listenerPingPongSocket.pongs.isEmpty());
        assertTrue(clientEndpoint.pongMessages.isEmpty());
        assertTrue(clientEndpoint.pingMessages.isEmpty());
    }

    @WebSocket
    public static class AnnotatedPingPongSocket
    {
        private final BlockingQueue<ByteBuffer> pings = new BlockingArrayQueue<>();
        private final BlockingQueue<ByteBuffer> pongs = new BlockingArrayQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);

        @OnWebSocketPing
        public void onPing(Session session, ByteBuffer payload)
        {
            assertNotNull(session);
            pings.add(payload);
        }

        @OnWebSocketPong
        public void onPong(Session session, ByteBuffer payload)
        {
            assertNotNull(session);
            pongs.add(payload);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            closed.countDown();
        }
    }

    public static class ListenerPingPongSocket implements Session.Listener.AutoDemanding
    {
        private final BlockingQueue<ByteBuffer> pings = new BlockingArrayQueue<>();
        private final BlockingQueue<ByteBuffer> pongs = new BlockingArrayQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            pings.add(payload);
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            pongs.add(payload);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closed.countDown();
        }
    }
}
