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

package org.eclipse.jetty.ee9.websocket.tests;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientDisconnectTest
{
    private final CompletableFuture<ServerSocket> _serverSocketFuture = new CompletableFuture<>();
    private final Duration _serverIdleTimeout = Duration.ofSeconds(5);
    private final int _messageSize = 5 * 1024 * 1024;
    private Server _server;
    private ArrayByteBufferPool.Tracking _bufferPool;
    private ServerConnector _connector;
    private WebSocketClient _client;

    @WebSocket
    public class ServerSocket extends EchoSocket
    {
        @Override
        public void onOpen(Session session)
        {
            _serverSocketFuture.complete(this);
            super.onOpen(session);
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        _client = new WebSocketClient();
        _bufferPool = new ArrayByteBufferPool.Tracking();
        _server = new Server(null, null, _bufferPool);
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, container) ->
        {
            container.addMapping("/", (req, resp) -> new ServerSocket());
            container.setIdleTimeout(_serverIdleTimeout);
            container.setMaxBinaryMessageSize(_messageSize);
        }));
        _server.setHandler(contextHandler);

        _server.start();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        try
        {
            assertThat("Detected leaks: " + _bufferPool.dumpLeaks(), _bufferPool.getLeaks().size(), is(0));
        }
        finally
        {
            LifeCycle.stop(_client);
            LifeCycle.stop(_server);
        }
    }

    @Test
    public void testBuffersAfterIncompleteMessage() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());

        // Open connection to the server.
        Session session = _client.connect(new WebSocketAdapter(), uri).get(5, TimeUnit.SECONDS);
        ServerSocket serverSocket = _serverSocketFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(serverSocket);

        // Send partial payload to server then abruptly close the connection.
        byte[] bytes = new byte[300_000];
        Arrays.fill(bytes, (byte)'x');
        session.setMaxBinaryMessageSize(_messageSize);
        session.getRemote().sendPartialBytes(BufferUtil.toBuffer(bytes), false);
        session.disconnect();

        // Wait for the server to close its session.
        assertTrue(serverSocket.closeLatch.await(_serverIdleTimeout.toSeconds() + 1, TimeUnit.SECONDS));
    }
}
