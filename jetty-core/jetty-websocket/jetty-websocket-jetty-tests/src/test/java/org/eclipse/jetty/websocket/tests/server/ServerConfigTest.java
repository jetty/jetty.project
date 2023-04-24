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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.WebSocketConnection;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConfigTest
{
    private Server server;
    private WebSocketClient client;
    private ServerConnector connector;
    private final ConnectionListener listener = new ConnectionListener();

    private static final String MESSAGE = "this message is over 20 characters long";
    private static final int INPUT_BUFFER_SIZE = 200;
    private static final int MAX_MESSAGE_SIZE = 20;
    private static final int IDLE_TIMEOUT = 500;

    private final EventSocket sessionConfigEndpoint = new SessionConfigEndpoint();
    private final EventSocket standardEndpoint = new EventSocket();

    private EventSocket getServerEndpoint(String path)
    {
        return switch (path)
            {
                case "servletConfig", "containerConfig" -> standardEndpoint;
                case "sessionConfig" -> sessionConfigEndpoint;
                default -> throw new IllegalStateException();
            };
    }

    public static Stream<Arguments> data()
    {
        return Stream.of("servletConfig", "containerConfig", "sessionConfig").map(Arguments::of);
    }

    @WebSocket
    public static class SessionConfigEndpoint extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            session.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
            session.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
            session.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
            session.setInputBufferSize(INPUT_BUFFER_SIZE);
            super.onOpen(session);
        }
    }

    public static class ConfigWebSocketUpgradeHandler
    {
        public static WebSocketUpgradeHandler from(Server server, ContextHandler context, Object wsEndPoint)
        {
            return WebSocketUpgradeHandler.from(server, context)
                .configure(container ->
                {
                    container.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
                    container.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
                    container.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
                    container.setInputBufferSize(INPUT_BUFFER_SIZE);
                    container.addMapping("/", (rq, rs, cb) -> wsEndPoint);
                });
        }
    }

    public static class SessionConfigWebSocketUpgradeHandler
    {
        public static WebSocketUpgradeHandler from(Server server, ContextHandler context, Object wsEndPoint)
        {
            return WebSocketUpgradeHandler.from(server, context)
                .configure(container ->
                    container.addMapping("/", (rq, rs, cb) -> wsEndPoint));
        }
    }

    public static class ConnectionListener implements Connection.Listener
    {
        private final AtomicInteger opened = new AtomicInteger(0);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onOpened(Connection connection)
        {
            if (connection instanceof WebSocketConnection)
                opened.incrementAndGet();
        }

        @Override
        public void onClosed(Connection connection)
        {
            if (connection instanceof WebSocketConnection)
                closed.countDown();
        }

        public void assertClosed() throws Exception
        {
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertThat(opened.get(), is(1));
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.addBean(listener);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        PathMappingsHandler pathsHandler = new PathMappingsHandler();
        context.setHandler(pathsHandler);
        pathsHandler.addMapping(new ServletPathSpec("/servletConfig"), ConfigWebSocketUpgradeHandler.from(server, context, standardEndpoint));
        pathsHandler.addMapping(new ServletPathSpec("/sessionConfig"), SessionConfigWebSocketUpgradeHandler.from(server, context, sessionConfigEndpoint));
        pathsHandler.addMapping(new ServletPathSpec("/"), WebSocketUpgradeHandler.from(server, context)
            .configure(container ->
            {
                container.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
                container.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
                container.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
                container.setInputBufferSize(INPUT_BUFFER_SIZE);
                container.addMapping("/containerConfig", (rq, rs, cb) -> standardEndpoint);
            }));

        server.setHandler(context);
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testInputBufferSize(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/" + path);
        EventSocket clientEndpoint = new EventSocket();
        EventSocket serverEndpoint = getServerEndpoint(path);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);

        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        WebSocketCoreSession coreSession = (WebSocketCoreSession)((WebSocketSession)serverEndpoint.session).getCoreSession();
        WebSocketConnection connection = coreSession.getConnection();

        assertThat(connection.getInputBufferSize(), is(INPUT_BUFFER_SIZE));

        serverEndpoint.session.close();
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertNull(serverEndpoint.error);

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));

        listener.assertClosed();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMaxBinaryMessageSize(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/" + path);
        EventSocket clientEndpoint = new EventSocket();
        EventSocket serverEndpoint = getServerEndpoint(path);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        ByteBuffer buffer = BufferUtil.toBuffer(MESSAGE);
        clientEndpoint.session.sendBinary(buffer, Callback.NOOP);
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(serverEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));

        listener.assertClosed();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testIdleTimeout(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/" + path);
        EventSocket clientEndpoint = new EventSocket();
        EventSocket serverEndpoint = getServerEndpoint(path);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        clientEndpoint.session.sendText("hello world", Callback.NOOP);
        String msg = serverEndpoint.textMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(msg, is("hello world"));
        Thread.sleep(IDLE_TIMEOUT + 500);

        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.error, instanceOf(WebSocketTimeoutException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.SHUTDOWN));

        listener.assertClosed();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMaxTextMessageSize(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/" + path);
        EventSocket clientEndpoint = new EventSocket();
        EventSocket serverEndpoint = getServerEndpoint(path);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        clientEndpoint.session.sendText(MESSAGE, Callback.NOOP);
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(serverEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));

        listener.assertClosed();
    }
}
