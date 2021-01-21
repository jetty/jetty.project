//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorCloseTest
{
    private Server server = new Server();
    private WebSocketClient client = new WebSocketClient();
    private ThrowingSocket serverSocket = new ThrowingSocket();
    private CountDownLatch serverCloseListener = new CountDownLatch(1);
    private ServerConnector connector;
    private URI serverUri;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addMapping("/", (req, resp) -> serverSocket);
            container.getFactory().addSessionListener(new WebSocketSessionListener()
            {
                @Override
                public void onSessionClosed(WebSocketSession session)
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
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.error.getMessage(), is("throwing from onOpen"));

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
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
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsAfterOnOpenError(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add("onOpen");
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketSession.class))
        {
            client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsAfterOnMessageError(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add("onMessage");
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketSession.class))
        {
            clientSocket.session.getRemote().sendString("trigger onMessage error");
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
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
        serverSocket.session.setIdleTimeout(1000);

        // Wait for server to timeout.
        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketSession.class))
        {
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsOnRemoteDisconnect(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketSession.class))
        {
            clientSocket.session.disconnect();
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onClose"})
    public void testWebSocketThrowsOnLocalDisconnect(String methodToThrow) throws Exception
    {
        serverSocket.methodsToThrow.add(methodToThrow);
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, serverUri).get(5, TimeUnit.SECONDS);

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketSession.class))
        {
            serverSocket.session.disconnect();
            assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // Check we have stopped the WebSocketSession properly.
        assertFalse(serverSocket.session.isOpen());
        serverCloseListener.await(5, TimeUnit.SECONDS);
        assertTrue(((WebSocketSession)serverSocket.session).isStopped());
    }
}
