//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JettyWebSocketNegotiationTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, null);
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

    @Test
    public void testBadRequest() throws Exception
    {
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(contextHandler.getServletContext());
        container.addMapping("/", (req, resp) -> new EchoSocket());

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();

        UpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("permessage-deflate;invalidParameter");

        CompletableFuture<Session> connect = client.connect(socket, uri, upgradeRequest);
        Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
        assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
        assertThat(t.getMessage(), containsString("400 Bad Request"));
    }

    @Test
    public void testServerError() throws Exception
    {
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(contextHandler.getServletContext());
        container.addMapping("/", (req, resp) ->
        {
            resp.setAcceptedSubProtocol("errorSubProtocol");
            return new EchoSocket();
        });

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannel.class))
        {
            CompletableFuture<Session> connect = client.connect(socket, uri);
            Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
            assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
            assertThat(t.getMessage(), containsString("500 Server Error"));
        }
    }
}
