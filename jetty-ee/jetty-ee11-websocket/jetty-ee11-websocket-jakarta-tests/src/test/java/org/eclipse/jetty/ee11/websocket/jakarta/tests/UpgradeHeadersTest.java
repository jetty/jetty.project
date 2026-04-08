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

package org.eclipse.jetty.ee11.websocket.jakarta.tests;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee11.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeHeadersTest
{
    private Server _server;
    private JakartaWebSocketClientContainer _client;
    private ServerConnector _connector;

    public static class MyEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
        }
    }

    public void start(ServerEndpointConfig.Configurator configurator) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        _server.setHandler(contextHandler);
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addEndpoint(ServerEndpointConfig.Builder
                .create(MyEndpoint.class, "/")
                .configurator(configurator)
                .build());
        });

        _server.start();
        _client = new JakartaWebSocketClientContainer();
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
        ClientEndpointConfig.Configurator configurator = new ClientEndpointConfig.Configurator()
        {
            @Override
            public void beforeRequest(Map<String, List<String>> headers)
            {
                // Verify that existing headers can be accessed in a case-insensitive way.
                if (headers.get("cOnnEcTiOn") == null)
                    throw new IllegalStateException("No Connection Header on client Request");
                headers.put("sentHeader", List.of("value123"));
            }

            @Override
            public void afterResponse(HandshakeResponse hr)
            {
                if (hr.getHeaders().get("MyHeAdEr") == null)
                    throw new IllegalStateException("No custom Header on HandshakeResponse");
                if (hr.getHeaders().get("cOnnEcTiOn") == null)
                    throw new IllegalStateException("No Connection Header on HandshakeRequest");
            }
        };

        start(new ServerEndpointConfig.Configurator()
        {
            @Override
            public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
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

                super.modifyHandshake(sec, request, response);
            }
        });

        WSEndpointTracker clientEndpoint = new WSEndpointTracker(){};
        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create().configurator(configurator).build();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());

        // If any of the above throw it would fail to upgrade to websocket.
        Session session = _client.connectToServer(clientEndpoint, clientConfig, uri);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
