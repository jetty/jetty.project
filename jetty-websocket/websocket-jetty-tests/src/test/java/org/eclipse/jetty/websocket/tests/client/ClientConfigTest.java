//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.tests.EchoSocket;
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

public class ClientConfigTest
{
    private Server server;
    private WebSocketClient client;
    private ServerConnector connector;

    private EchoSocket serverSocket = new EchoSocket();

    private static String message = "this message is over 20 characters long";
    private final int inputBufferSize = 200;
    private final int maxMessageSize = 20;
    private final int idleTimeout = 500;

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

    @WebSocket(idleTimeout = idleTimeout, maxTextMessageSize = maxMessageSize, maxBinaryMessageSize = maxMessageSize, inputBufferSize = inputBufferSize, batchMode = BatchMode.ON)
    public class AnnotatedConfigEndpoint extends EventSocket
    {
    }

    @WebSocket
    public class SessionConfigEndpoint extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            session.setIdleTimeout(Duration.ofMillis(idleTimeout));
            session.setMaxTextMessageSize(maxMessageSize);
            session.setMaxBinaryMessageSize(maxMessageSize);
            session.setInputBufferSize(inputBufferSize);
            super.onOpen(session);
        }
    }

    public EventSocket getClientSocket(String param)
    {
        switch (param)
        {
            case "clientConfig":
                client.setInputBufferSize(inputBufferSize);
                client.setMaxBinaryMessageSize(maxMessageSize);
                client.setIdleTimeout(Duration.ofMillis(idleTimeout));
                client.setMaxTextMessageSize(maxMessageSize);
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

        assertThat(connection.getInputBufferSize(), is(inputBufferSize));

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
        clientEndpoint.session.getRemote().sendBytes(BufferUtil.toBuffer(message));
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
        Thread.sleep(idleTimeout + 500);

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
        clientEndpoint.session.getRemote().sendString(message);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(clientEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));
    }
}
