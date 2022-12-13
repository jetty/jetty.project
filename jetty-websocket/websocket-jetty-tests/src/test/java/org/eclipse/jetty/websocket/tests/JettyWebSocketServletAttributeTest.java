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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketServletAttributeTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private final EchoSocket serverEndpoint = new EchoSocket();

    @BeforeEach
    public void before()
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        client = new WebSocketClient();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    public void start(JettyWebSocketServletContainerInitializer.Configurator configurator) throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, configurator);

        server.start();
        client.start();
    }

    @Test
    public void testAttributeSetInNegotiation() throws Exception
    {
        start((context, container) -> container.addMapping("/", (req, resp) ->
        {
            req.setServletAttribute("myWebSocketCustomAttribute", "true");
            return serverEndpoint;
        }));

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket clientEndpoint = new EventSocket();
        client.connect(clientEndpoint, uri);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        // We should have our custom attribute on the upgraded request, which was set in the negotiation.
        JettyServerUpgradeRequest upgradeRequest = (JettyServerUpgradeRequest)serverEndpoint.session.getUpgradeRequest();
        assertThat(upgradeRequest.getServletAttribute("myWebSocketCustomAttribute"), is("true"));

        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
