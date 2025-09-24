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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicMappingTest
{
    private Server server;
    private ServerConnector serverConnector;
    private JakartaWebSocketClientContainer client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        contextHandler = new ServletContextHandler();
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (servletContext, wsContainer) ->
        {
            wsContainer.addEndpoint(ServerEndpointConfig.Builder.create(EchoSocket.class, "/mapping1").build());
            wsContainer.addEndpoint(ServerEndpointConfig.Builder.create(EchoSocket.class, "/mapping2").build());
            wsContainer.addEndpoint(ServerEndpointConfig.Builder.create(EchoSocket.class, "/mapping3").build());
        });
        server.setHandler(contextHandler);

        server.start();
        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testRemoveMapping() throws Exception
    {
        serverConnector.setIdleTimeout(5);
        client.setDefaultMaxSessionIdleTimeout(5);

        // Test each mapping works.
        testEcho("/mapping1");
        testEcho("/mapping2");
        testEcho("/mapping3");

        // Remove mapping2.
        WebSocketMappings webSocketMappings = contextHandler.getBean(WebSocketMappings.class);
        PathSpec pathSpec = new UriTemplatePathSpec("/mapping2");
        webSocketMappings.removeMapping(pathSpec);

        // Test that mapping1 and mapping3 still work, but mapping2 gives a 404 response.
        testEcho("/mapping1");
        testEcho("/mapping3");
        IOException e = assertThrows(IOException.class, () -> testEcho("/mapping2"));
        assertThat(e.getMessage(), Matchers.containsString("404 Not Found"));
    }

    @SuppressWarnings("resource")
    private void testEcho(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + serverConnector.getLocalPort() + path);
        EventSocket clientEndpoint = new EventSocket();
        client.connectToServer(clientEndpoint, uri);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }
}
