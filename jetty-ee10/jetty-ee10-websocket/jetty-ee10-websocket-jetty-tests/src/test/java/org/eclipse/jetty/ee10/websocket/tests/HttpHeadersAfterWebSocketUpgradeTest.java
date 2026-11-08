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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpHeadersAfterWebSocketUpgradeTest
{
    Server _server;
    ServerConnector _connector;
    private WebSocketClient _client;

    public void before(Consumer<Session> onOpen) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler("/");
        _server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (servletContext, container) ->
            container.addMapping("/", (req, resp) -> new HeadersSessionListener(onOpen)));

        _client = new WebSocketClient();
        _client.start();
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @WebSocket
    public static class HeadersSessionListener extends EventSocket
    {
        Consumer<Session> _onOpen;

        public HeadersSessionListener(Consumer<Session> onOpen)
        {
            _onOpen = onOpen;
        }

        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            _onOpen.accept(session);
        }
    }

    @Test
    public void testHttpHeaderInOnOpen() throws Exception
    {
        before(session ->
        {
            // Try to access a specific header from onOpen.
            String customHeaderValue = session.getUpgradeRequest().getHeader("CustomHeader");
            session.sendText(customHeaderValue, Callback.NOOP);
        });

        EventSocket clientEndpoint = new EventSocket();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest(URI.create("ws://localhost:" + _connector.getLocalPort()));
        upgradeRequest.setHeader("CustomHeader", "foobar");
        Session session = _client.connect(clientEndpoint, upgradeRequest).get(5, TimeUnit.SECONDS);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        // In onOpen the server should send a message of its headers.
        String message = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(message, equalTo("foobar"));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }
}
