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

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LargeDeflateTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;
    private final EventSocket _serverSocket = new EventSocket();

    @BeforeEach
    void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler handler = new ServletContextHandler();
        _server.insertHandler(handler);
        JettyWebSocketServletContainerInitializer.configure(handler, (servletContext, container) ->
        {
            container.setIdleTimeout(Duration.ofDays(1));
            container.setMaxFrameSize(Integer.MAX_VALUE);
            container.setMaxBinaryMessageSize(Integer.MAX_VALUE);
            container.addMapping("/", (req, resp) -> _serverSocket);
        });

        _server.start();
        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    void testDeflate() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("permessage-deflate");

        EventSocket clientSocket = new EventSocket();
        Session session = _client.connect(clientSocket, URI.create("ws://localhost:" + _connector.getLocalPort() + "/ws"), upgradeRequest).get();
        ByteBuffer sentMessage = largePayloads();
        session.getRemote().sendBytes(sentMessage);
        session.close(StatusCode.NORMAL, "close from test");

        assertTrue(_serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(_serverSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(_serverSocket.closeReason, is("close from test"));

        ByteBuffer message = _serverSocket.binaryMessages.poll(1, TimeUnit.SECONDS);
        assertThat(message, is(sentMessage));
    }

    private static ByteBuffer largePayloads()
    {
        var bytes = new byte[4 * 1024 * 1024];
        new Random(42).nextBytes(bytes);
        return BufferUtil.toBuffer(bytes);
    }
}
