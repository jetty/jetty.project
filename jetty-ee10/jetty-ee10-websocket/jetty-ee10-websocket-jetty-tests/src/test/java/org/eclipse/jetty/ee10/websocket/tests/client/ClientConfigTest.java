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

package org.eclipse.jetty.ee10.websocket.tests.client;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.BatchMode;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.api.exceptions.MessageTooLargeException;
import org.eclipse.jetty.ee10.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.common.WebSocketSession;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.tests.EchoSocket;
import org.eclipse.jetty.ee10.websocket.tests.EventSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
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

public class ClientConfigTest
{
    private Server server;
    private WebSocketClient client;
    private ServerConnector connector;
    private final EchoSocket serverSocket = new EchoSocket();

    private static final String MESSAGE = "this message is over 20 characters long";
    private static final int INPUT_BUFFER_SIZE = 200;
    private static final int MAX_MESSAGE_SIZE = 20;
    private static final int IDLE_TIMEOUT = 500;

    public static Stream<Arguments> data()
    {
        return Stream.of("clientConfig", "annotatedConfig", "sessionConfig").map(Arguments::of);
    }

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler,
            (context, container) -> container.addMapping("/", (req, resp) -> serverSocket));

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

    @WebSocket(idleTimeout = IDLE_TIMEOUT, maxTextMessageSize = MAX_MESSAGE_SIZE, maxBinaryMessageSize = MAX_MESSAGE_SIZE, inputBufferSize = INPUT_BUFFER_SIZE, batchMode = BatchMode.ON)
    public static class AnnotatedConfigEndpoint extends EventSocket
    {
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

    public EventSocket getClientSocket(String param)
    {
        switch (param)
        {
            case "clientConfig":
                client.setInputBufferSize(INPUT_BUFFER_SIZE);
                client.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
                client.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
                client.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
                return new EventSocket();

            case "annotatedConfig":
                return new AnnotatedConfigEndpoint();

            case "sessionConfig":
                return new SessionConfigEndpoint();

            default:
                throw new IllegalStateException();
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testInputBufferSize(String param) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndpoint = getClientSocket(param);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);

        WebSocketCoreSession coreSession = (WebSocketCoreSession)((WebSocketSession)clientEndpoint.session).getCoreSession();
        WebSocketConnection connection = coreSession.getConnection();

        assertThat(connection.getInputBufferSize(), is(INPUT_BUFFER_SIZE));

        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertNull(clientEndpoint.error);

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMaxBinaryMessageSize(String param) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndpoint = getClientSocket(param);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        clientEndpoint.session.getRemote().sendBytes(BufferUtil.toBuffer(MESSAGE));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(clientEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testIdleTimeout(String param) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndpoint = getClientSocket(param);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        clientEndpoint.session.getRemote().sendString("hello world");
        Thread.sleep(IDLE_TIMEOUT + 500);

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(WebSocketTimeoutException.class));

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.SHUTDOWN));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMaxTextMessageSize(String param) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndpoint = getClientSocket(param);
        CompletableFuture<Session> connect = client.connect(clientEndpoint, uri);

        connect.get(5, TimeUnit.SECONDS);
        clientEndpoint.session.getRemote().sendString(MESSAGE);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(clientEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));
    }
}
