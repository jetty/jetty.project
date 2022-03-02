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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeRequestResponseTest
{
    private ServerConnector connector;
    private JakartaWebSocketClientContainer client;
    private static CompletableFuture<EventSocket> serverSocketFuture;

    @ServerEndpoint("/")
    public static class ServerSocket extends EchoSocket
    {
        public ServerSocket()
        {
            serverSocketFuture.complete(this);
        }
    }

    public static class PermessageDeflateConfig extends ClientEndpointConfig.Configurator
    {
        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            headers.put(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(), Collections.singletonList("permessage-deflate"));
        }
    }

    @ClientEndpoint(configurator = PermessageDeflateConfig.class)
    public static class ClientSocket extends EventSocket
    {
    }

    @BeforeEach
    public void start() throws Exception
    {
        Server server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        serverSocketFuture = new CompletableFuture<>();

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        server.setHandler(contextHandler);
        contextHandler.setContextPath("/");
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(ServerSocket.class));

        client = new JakartaWebSocketClientContainer();
        server.start();
        client.start();
    }

    @Test
    public void testUpgradeRequestResponse() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        EventSocket socket = new ClientSocket();

        Session clientSession = client.connectToServer(socket, uri);
        EventSocket serverSocket = serverSocketFuture.get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // The user principal is found on the base UpgradeRequest.
        assertDoesNotThrow(clientSession::getUserPrincipal);
        assertDoesNotThrow(serverSocket.session::getUserPrincipal);

        clientSession.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
