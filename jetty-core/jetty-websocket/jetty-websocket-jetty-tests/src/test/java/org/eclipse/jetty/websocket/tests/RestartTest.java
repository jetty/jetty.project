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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestartTest
{
    private Server _server;
    private ServerConnector _connector;
    private ContextHandler _contextHandler;
    private WebSocketUpgradeHandler _upgradeHandler;
    private WebSocketClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _contextHandler = new ContextHandler("/");
        _upgradeHandler = WebSocketUpgradeHandler.from(_server, _contextHandler,
            container -> container.addMapping("/", (req, resp, cb) -> new EchoSocket()));
        _contextHandler.setHandler(_upgradeHandler);
        _server.setHandler(_contextHandler);

        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testEchoStopStartEcho() throws Exception
    {
        testEcho();
        _server.stop();
        assertThat(_upgradeHandler.getServerWebSocketContainer().dump(), containsString("PathMappings[size=0]"));
        _server.start();
        testEcho();
    }

    @Test
    public void testStopStartGet() throws Exception
    {
        assertNotNull(ServerWebSocketContainer.get(_contextHandler.getContext()));
        _server.stop();
        assertNull(ServerWebSocketContainer.get(_contextHandler.getContext()));
        _server.start();
        assertNotNull(ServerWebSocketContainer.get(_contextHandler.getContext()));
    }

    private void testEcho() throws Exception
    {
        EchoSocket clientEndpoint = new EchoSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        _client.connect(clientEndpoint, uri);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        clientEndpoint.session.sendText("hello world", Callback.NOOP);
        String message = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(message, equalTo("hello world"));
        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
