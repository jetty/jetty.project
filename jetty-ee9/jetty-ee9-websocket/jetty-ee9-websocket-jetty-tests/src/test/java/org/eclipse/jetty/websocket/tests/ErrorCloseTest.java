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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorCloseTest
{
    private final Server server = new Server();
    private final WebSocketClient client = new WebSocketClient();
    private final ThrowingSocket serverSocket = new ThrowingSocket();
    private final CountDownLatch serverCloseListener = new CountDownLatch(1);
    private URI serverUri;

    @BeforeEach
    public void start() throws Exception
    {
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addMapping("/", (req, resp) -> serverSocket);
            container.addSessionListener(new WebSocketSessionListener()
            {
                @Override
                public void onWebSocketSessionClosed(Session session)
                {
                    serverCloseListener.countDown();
                }
            });
        });
        server.setHandler(contextHandler);

        server.start();
        client.start();
        serverUri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @WebSocket
    public static class ThrowingSocket extends EventSocket
    {
        public List<String> methodsToThrow = new ArrayList<>();

        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            if (methodsToThrow.contains("onOpen"))
                throw new RuntimeException("throwing from onOpen");
        }

        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            if (methodsToThrow.contains("onMessage"))
                throw new RuntimeException("throwing from onMessage");
        }

        @Override
        public void onClose(int statusCode, String reason)
        {
            super.onClose(statusCode, reason);
            if (methodsToThrow.contains("onClose"))
                throw new RuntimeException("throwing from onClose");
        }

        @Override
        public void onError(Throwable cause)
        {
            super.onError(cause);
            if (methodsToThrow.contains("onError"))
                throw new RuntimeException("throwing from onError");
        }
    }

    @Test
    public void testOnOpenThrows() throws Exception
    {
        serverSocket.methodsToThrow.add("onOpen");
        EventSocket clientSocket = new EventSocket();

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(serverSocket.error.getMessage(), containsString("throwing from onOpen"));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testOnMessageThrows() throws Exception
    {
        serverSocket.methodsToThrow.add("onMessage");
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);
        clientSocket.session.getRemote().sendString("trigger onMessage error");

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.error.getMessage(), is("throwing from onMessage"));

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsAfterOnOpenError(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add("onOpen");
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();

        try (StacklessLogging ignored = new StacklessLogging(WebSocketSession.class))
        {
            client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsAfterOnMessageError(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add("onMessage");
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        try (StacklessLogging ignored = new StacklessLogging(WebSocketSession.class))
        {
            clientSocket.session.getRemote().sendString("trigger onMessage error");
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsOnTimeout(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        // Set a short idleTimeout on the server.
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        serverSocket.session.setIdleTimeout(Duration.ofSeconds(1));

        // Wait for server to timeout.
        try (StacklessLogging ignored = new StacklessLogging(WebSocketSession.class))
        {
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsOnRemoteDisconnect(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        try (StacklessLogging ignored = new StacklessLogging(WebSocketSession.class))
        {
            clientSocket.session.disconnect();
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsOnLocalDisconnect(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        try (StacklessLogging ignored = new StacklessLogging(WebSocketSession.class))
        {
            serverSocket.session.disconnect();
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
    }
}
