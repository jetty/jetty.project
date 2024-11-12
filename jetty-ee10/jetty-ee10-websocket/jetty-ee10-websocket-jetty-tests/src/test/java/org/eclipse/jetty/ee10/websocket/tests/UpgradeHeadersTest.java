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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeHeadersTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    public void start(JettyWebSocketCreator creator) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (servletContext, container) ->
            container.addMapping("/", creator));
        _server.setHandler(contextHandler);

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
    public void testCaseInsensitiveUpgradeHeaders() throws Exception
    {
        start((request, response) ->
        {
            // Verify that existing headers can be accessed in a case-insensitive way.
            if (request.getHeaders().get("cOnnEcTiOn") == null)
                throw new IllegalStateException("No Connection Header on HandshakeRequest");
            if (response.getHeaders().get("sErVeR") == null)
                throw new IllegalStateException("No Server Header on HandshakeResponse");

            // Verify custom header sent from client.
            if (request.getHeaders().get("SeNtHeadEr") == null)
                throw new IllegalStateException("No sent Header on HandshakeResponse");

            // Add custom response header.
            response.getHeaders().put("myHeader", List.of("foobar"));
            if (response.getHeaders().get("MyHeAdEr") == null)
                throw new IllegalStateException("No custom Header on HandshakeResponse");

            return new EchoSocket();
        });

        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());

        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.getHeaders().put("sentHeader", List.of("value123"));
        if (clientUpgradeRequest.getHeaders().get("SenTHeaDer") == null)
            throw new IllegalStateException("No custom Header on ClientUpgradeRequest");

        JettyUpgradeListener upgradeListener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeRequest(Request request)
            {
                // Verify that existing headers can be accessed in a case-insensitive way.
                if (request.getHeaders().get("cOnnEcTiOn") == null)
                    throw new IllegalStateException("No Connection Header on client Request");
                if (request.getHeaders().get("SenTHeaDer") == null)
                    throw new IllegalStateException("No custom Header on ClientUpgradeRequest");
            }

            @Override
            public void onHandshakeResponse(Request request, Response response)
            {
                if (response.getHeaders().get("MyHeAdEr") == null)
                    throw new IllegalStateException("No custom Header on HandshakeResponse");
                if (response.getHeaders().get("cOnnEcTiOn") == null)
                    throw new IllegalStateException("No Connection Header on HandshakeRequest");
            }
        };

        // If any of the above throw it would fail to upgrade to websocket.
        assertNotNull(_client.connect(clientEndpoint, uri, clientUpgradeRequest, upgradeListener).get(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
