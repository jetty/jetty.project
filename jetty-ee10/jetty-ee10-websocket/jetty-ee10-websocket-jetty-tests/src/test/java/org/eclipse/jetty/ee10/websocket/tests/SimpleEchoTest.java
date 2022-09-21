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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SimpleEchoTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, container) ->
        {
            container.setIdleTimeout(Duration.ZERO);
            container.addMapping("/", EchoSocket.class);
        }));
        _server.setHandler(contextHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testEcho() throws Exception
    {
        int timeout = 10000;
        _client.setIdleTimeout(Duration.ofSeconds(timeout));
        _client.setConnectTimeout(Duration.ofSeconds(timeout).toMillis());

        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(timeout, TimeUnit.SECONDS);
        session.setIdleTimeout(Duration.ofSeconds(timeout));

        String message = "hello world 1234";
        session.getRemote().sendString(message);
        String received = clientEndpoint.textMessages.poll(timeout, TimeUnit.SECONDS);
        assertThat(received, equalTo(message));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(timeout, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }
}
