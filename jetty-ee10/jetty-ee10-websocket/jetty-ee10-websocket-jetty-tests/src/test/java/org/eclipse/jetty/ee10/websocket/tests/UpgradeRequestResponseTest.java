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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.UpgradeRequest;
import org.eclipse.jetty.ee10.websocket.api.UpgradeResponse;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeRequestResponseTest
{
    private ServerConnector connector;
    private WebSocketClient client;
    private EventSocket serverSocket;

    @BeforeEach
    public void start() throws Exception
    {
        Server server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        serverSocket = new EchoSocket();

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        server.setHandler(contextHandler);
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addMapping("/", (req, resp) -> serverSocket));

        client = new WebSocketClient();

        server.start();
        client.start();
    }

    @Test
    public void testClientUpgradeRequestResponse() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.addExtensions("permessage-deflate");

        CompletableFuture<Session> connect = client.connect(socket, uri, request);
        Session session = connect.get(5, TimeUnit.SECONDS);
        UpgradeRequest upgradeRequest = session.getUpgradeRequest();
        UpgradeResponse upgradeResponse = session.getUpgradeResponse();

        assertNotNull(upgradeRequest);
        assertThat(upgradeRequest.getHeader(HttpHeader.UPGRADE.asString()), is("websocket"));
        assertThat(upgradeRequest.getHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString()), is("permessage-deflate"));
        assertThat(upgradeRequest.getExtensions().size(), is(1));
        assertThat(upgradeRequest.getExtensions().get(0).getName(), is("permessage-deflate"));

        assertNotNull(upgradeResponse);
        assertThat(upgradeResponse.getStatusCode(), is(HttpStatus.SWITCHING_PROTOCOLS_101));
        assertThat(upgradeResponse.getHeader(HttpHeader.UPGRADE.asString()), is("websocket"));
        assertThat(upgradeResponse.getHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString()), is("permessage-deflate"));
        assertThat(upgradeResponse.getExtensions().size(), is(1));
        assertThat(upgradeResponse.getExtensions().get(0).getName(), is("permessage-deflate"));

        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerUpgradeRequestResponse() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.addExtensions("permessage-deflate");

        CompletableFuture<Session> connect = client.connect(socket, uri, request);
        Session session = connect.get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        UpgradeRequest upgradeRequest = serverSocket.session.getUpgradeRequest();
        UpgradeResponse upgradeResponse = serverSocket.session.getUpgradeResponse();

        assertNotNull(upgradeRequest);
        assertThat(upgradeRequest.getHeader(HttpHeader.UPGRADE.asString()), is("websocket"));
        assertThat(upgradeRequest.getHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString()), is("permessage-deflate"));
        assertThat(upgradeRequest.getExtensions().size(), is(1));
        assertThat(upgradeRequest.getExtensions().get(0).getName(), is("permessage-deflate"));

        assertNotNull(upgradeResponse);
        /* TODO: The HttpServletResponse is eventually recycled so we lose this information.
        assertThat(upgradeResponse.getStatusCode(), is(HttpStatus.SWITCHING_PROTOCOLS_101));
        assertThat(upgradeResponse.getHeader(HttpHeader.UPGRADE.asString()), is("websocket"));
        assertThat(upgradeResponse.getHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString()), is("permessage-deflate"));
        assertThat(upgradeResponse.getExtensions().size(), is(1));
        assertThat(upgradeResponse.getExtensions().get(0).getName(), is("permessage-deflate"));
        */
        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
