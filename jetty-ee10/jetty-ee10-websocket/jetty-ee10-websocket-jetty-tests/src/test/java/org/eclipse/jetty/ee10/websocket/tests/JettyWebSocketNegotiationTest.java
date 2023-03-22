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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
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

        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
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

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannelState.class))
        {
            CompletableFuture<Session> connect = client.connect(socket, uri);
            Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
            assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
            assertThat(t.getMessage(), containsString("500 Server Error"));
        }
    }

    @Test
    public void testManualNegotiationInCreator() throws Exception
    {
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(contextHandler.getServletContext());
        container.addMapping("/", (req, resp) ->
        {
            long matchedExts = req.getExtensions().stream()
                .filter(ec -> "permessage-deflate".equals(ec.getName()))
                .filter(ec -> ec.getParameters().containsKey("client_no_context_takeover"))
                .count();
            assertThat(matchedExts, is(1L));

            // Manually drop the param so it is not negotiated in the extension stack.
            resp.setHeader("Sec-WebSocket-Extensions", "permessage-deflate");
            return new EchoSocket();
        });

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();
        AtomicReference<Response> responseReference = new AtomicReference<>();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("permessage-deflate;client_no_context_takeover");
        JettyUpgradeListener upgradeListener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeResponse(Request request, Response response)
            {
                responseReference.set(response);
            }
        };

        client.connect(socket, uri, upgradeRequest, upgradeListener).get(5, TimeUnit.SECONDS);
        Response response = responseReference.get();
        String extensions = response.getHeaders().get("Sec-WebSocket-Extensions");
        assertThat(extensions, is("permessage-deflate"));
    }
}
