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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncCloseTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;
    private CloseWaitingEndpoint _serverEndpoint;

    public interface CloseWaitingEndpoint
    {
        void close();
    }

    public static class ServerListenerMessageEndpoint implements Session.Listener, CloseWaitingEndpoint
    {
        private Session _session;
        private final CountDownLatch _closeLatch = new CountDownLatch(1);

        @Override
        public void onWebSocketOpen(Session session)
        {
            _session = session;
            _session.demand();
        }

        @Override
        public void onWebSocketText(String message)
        {
            _session.sendText(message, Callback.from(() -> _session.demand(), t -> _session.disconnect()));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, Callback callback)
        {
            new Thread(() ->
            {
                awaitClose(_closeLatch);
                _session.close(StatusCode.NORMAL, "custom close", callback);
            }).start();
        }

        @Override
        public void close()
        {
            _closeLatch.countDown();
        }
    }

    @WebSocket(autoDemand = false)
    public static class ServerAnnotatedMessageEndpoint implements CloseWaitingEndpoint
    {
        private Session _session;
        private final CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnWebSocketOpen
        public void onWebSocketOpen(Session session)
        {
            _session = session;
            _session.demand();
        }

        @OnWebSocketMessage
        public void onWebSocketText(String message)
        {
            _session.sendText(message, Callback.from(() -> _session.demand(), t -> _session.disconnect()));
        }

        @OnWebSocketClose
        public void onWebSocketClose(int statusCode, String reason, Callback callback)
        {
            new Thread(() ->
            {
                awaitClose(_closeLatch);
                _session.close(StatusCode.NORMAL, "custom close", callback);
            }).start();
        }

        @Override
        public void close()
        {
            _closeLatch.countDown();
        }
    }

    public static class ServerListenerFrameEndpoint implements Session.Listener, CloseWaitingEndpoint
    {
        private Session _session;
        private final CountDownLatch _closeLatch = new CountDownLatch(1);

        @Override
        public void onWebSocketOpen(Session session)
        {
            _session = session;
            _session.demand();
        }

        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            switch (frame.getEffectiveOpCode())
            {
                case OpCode.TEXT -> _session.sendPartialText(BufferUtil.toUTF8String(frame.getPayload()), frame.isFin(), Callback.from(callback, () -> _session.demand()));
                case OpCode.BINARY -> _session.sendPartialBinary(frame.getPayload(), frame.isFin(), Callback.from(callback, () -> _session.demand()));
                case OpCode.PING -> _session.sendPong(frame.getPayload(), Callback.from(callback, () -> _session.demand()));
                case OpCode.CLOSE -> new Thread(() ->
                {
                    awaitClose(_closeLatch);
                    _session.close(StatusCode.NORMAL, "custom close", callback);
                }).start();
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, Callback callback)
        {
            assertThat(_session.isOpen(), equalTo(false));
            callback.succeed();
        }

        @Override
        public void close()
        {
            _closeLatch.countDown();
        }
    }

    @WebSocket(autoDemand = false)
    public static class ServerAnnotatedFrameEndpoint implements CloseWaitingEndpoint
    {
        private Session _session;
        private final CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnWebSocketOpen
        public void onWebSocketOpen(Session session)
        {
            _session = session;
            _session.demand();
        }

        @OnWebSocketFrame
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            switch (frame.getEffectiveOpCode())
            {
                case OpCode.TEXT ->
                    _session.sendPartialText(BufferUtil.toUTF8String(frame.getPayload()), frame.isFin(), Callback.from(callback, () -> _session.demand()));
                case OpCode.BINARY -> _session.sendPartialBinary(frame.getPayload(), frame.isFin(), Callback.from(callback, () -> _session.demand()));
                case OpCode.PING -> _session.sendPong(frame.getPayload(), Callback.from(callback, () -> _session.demand()));
                case OpCode.CLOSE -> new Thread(() ->
                {
                    awaitClose(_closeLatch);
                    _session.close(StatusCode.NORMAL, "custom close", callback);
                }).start();
            }
        }

        @OnWebSocketClose
        public void onWebSocketClose(int statusCode, String reason, Callback callback)
        {
            assertThat(_session.isOpen(), equalTo(false));
            callback.succeed();
        }

        @Override
        public void close()
        {
            _closeLatch.countDown();
        }
    }

    public void start(CloseWaitingEndpoint serverEndpoint) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(_server, container ->
        {
            container.setIdleTimeout(Duration.ZERO);
            container.addMapping("/", (rq, rs, cb) -> serverEndpoint);
        });

        _server.setHandler(wsHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testListenerMessageEcho() throws Exception
    {
        CloseWaitingEndpoint serverEndpoint = new ServerListenerMessageEndpoint();
        start(serverEndpoint);
        testEchoAndClose(serverEndpoint);
    }

    @Test
    public void testAnnotatedMessageEcho() throws Exception
    {
        CloseWaitingEndpoint serverEndpoint = new ServerAnnotatedMessageEndpoint();
        start(serverEndpoint);
        testEchoAndClose(serverEndpoint);
    }

    @Test
    public void testListenerFrameEcho() throws Exception
    {
        CloseWaitingEndpoint serverEndpoint = new ServerListenerFrameEndpoint();
        start(serverEndpoint);
        testEchoAndClose(serverEndpoint);
    }

    @Test
    public void testAnnotatedFrameEcho() throws Exception
    {
        CloseWaitingEndpoint serverEndpoint = new ServerAnnotatedFrameEndpoint();
        start(serverEndpoint);
        testEchoAndClose(serverEndpoint);
    }

    private void testEchoAndClose(CloseWaitingEndpoint serverEndpoint) throws Exception
    {
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        String message = "hello world 1234";
        session.sendText(message, Callback.NOOP);
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, equalTo(message));

        // Initiate close from the client, test that the close is deferred.
        session.close();
        assertFalse(clientEndpoint.closeLatch.await(1, TimeUnit.SECONDS));

        // Test the server sends the correct close response and not the automatic one.
        serverEndpoint.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, equalTo("custom close"));
    }

    private static void awaitClose(CountDownLatch countDownLatch)
    {
        try
        {
            countDownLatch.await();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }
}
