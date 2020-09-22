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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
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
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
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
    private ConnectionListener listener = new ConnectionListener();

    private static String message = "this message is over 20 characters long";
    private static final int inputBufferSize = 200;
    private static final int maxMessageSize = 20;
    private static final int idleTimeout = 500;

    private EventSocket annotatedEndpoint = new AnnotatedConfigEndpoint();
    private EventSocket sessionConfigEndpoint = new SessionConfigEndpoint();
    private EventSocket standardEndpoint = new EventSocket();

    private EventSocket getServerEndpoint(String path)
    {
        switch (path)
        {
            case "servletConfig":
            case "containerConfig":
                return standardEndpoint;
            case "annotatedConfig":
                return annotatedEndpoint;
            case "sessionConfig":
                return sessionConfigEndpoint;
            default:
                throw new IllegalStateException();
        }
    }

    public static Stream<Arguments> data()
    {
        return Stream.of("servletConfig", "annotatedConfig", "containerConfig", "sessionConfig").map(Arguments::of);
    }

    @WebSocket(idleTimeout = idleTimeout, maxTextMessageSize = maxMessageSize, maxBinaryMessageSize = maxMessageSize, inputBufferSize = inputBufferSize, batchMode = BatchMode.ON)
    public static class AnnotatedConfigEndpoint extends EventSocket
    {
    }

    @WebSocket
    public static class SessionConfigEndpoint extends EventSocket
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

    public class WebSocketFactoryConfigServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.setIdleTimeout(Duration.ofMillis(idleTimeout));
            factory.setMaxTextMessageSize(maxMessageSize);
            factory.setMaxBinaryMessageSize(maxMessageSize);
            factory.setInputBufferSize(inputBufferSize);
            factory.addMapping("/", (req, resp) -> standardEndpoint);
        }
    }

    public class WebSocketAnnotatedConfigServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.addMapping("/", (req, resp) -> annotatedEndpoint);
        }
    }

    public class WebSocketSessionConfigServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.addMapping("/", (req, resp) -> sessionConfigEndpoint);
        }
    }

    public class ConnectionListener implements Connection.Listener
    {
        private AtomicInteger opened = new AtomicInteger(0);
        private CountDownLatch closed = new CountDownLatch(1);

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

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new WebSocketFactoryConfigServlet()), "/servletConfig");
        contextHandler.addServlet(new ServletHolder(new WebSocketAnnotatedConfigServlet()), "/annotatedConfig");
        contextHandler.addServlet(new ServletHolder(new WebSocketSessionConfigServlet()), "/sessionConfig");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.setIdleTimeout(Duration.ofMillis(idleTimeout));
            container.setMaxTextMessageSize(maxMessageSize);
            container.setMaxBinaryMessageSize(maxMessageSize);
            container.setInputBufferSize(inputBufferSize);
            container.addMapping("/containerConfig", (req, resp) -> standardEndpoint);
        });

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

        assertThat(connection.getInputBufferSize(), is(inputBufferSize));

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
        clientEndpoint.session.getRemote().sendBytes(BufferUtil.toBuffer(message));
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
        clientEndpoint.session.getRemote().sendString("hello world");
        String msg = serverEndpoint.textMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(msg, is("hello world"));
        Thread.sleep(idleTimeout + 500);

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
        clientEndpoint.session.getRemote().sendString(message);
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));

        assertThat(serverEndpoint.error, instanceOf(MessageTooLargeException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.MESSAGE_TOO_LARGE));

        listener.assertClosed();
    }
}
