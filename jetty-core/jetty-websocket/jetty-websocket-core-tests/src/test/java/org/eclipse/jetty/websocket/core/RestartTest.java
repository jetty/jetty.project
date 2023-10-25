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

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestartTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketCoreClient _client;
    private WebSocketUpgradeHandler _upgradeHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _upgradeHandler = new WebSocketUpgradeHandler(handler ->
            handler.addMapping("/", (req, resp, cb) -> new EchoFrameHandler()));
        _server.setHandler(_upgradeHandler);

        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        testEcho();
        _server.stop();
        assertThat(_upgradeHandler.dump(), containsString("PathMappings[size=0]"));
        _server.start();
        testEcho();
    }

    private void testEcho() throws Exception
    {
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        _client.connect(clientEndpoint, uri);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        clientEndpoint.sendText("hello world");
        String message = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(message, equalTo("hello world"));
        clientEndpoint.getCoreSession().close(Callback.NOOP);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
