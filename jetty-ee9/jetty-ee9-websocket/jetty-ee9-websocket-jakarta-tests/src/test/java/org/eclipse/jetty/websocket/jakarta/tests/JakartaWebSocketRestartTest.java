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

package org.eclipse.jetty.websocket.jakarta.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.Session;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JakartaWebSocketRestartTest
{
    private Server server;
    private ServerConnector connector;
    private JakartaWebSocketClientContainer client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testWebSocketRestart() throws Exception
    {
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(EchoSocket.class));
        server.start();

        int numEventListeners = contextHandler.getEventListeners().size();
        for (int i = 0; i < 100; i++)
        {
            server.stop();
            server.start();
            testEchoMessage();
        }

        // We have not accumulated websocket resources by restarting.
        assertThat(contextHandler.getEventListeners().size(), is(numEventListeners));
        assertThat(contextHandler.getContainedBeans(JakartaWebSocketServerContainer.class).size(), is(1));
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(1));
        assertNotNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNotNull(contextHandler.getServletContext().getAttribute(JakartaWebSocketServerContainer.JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE));

        // We have one filter, and it is a WebSocketUpgradeFilter.
        FilterHolder[] filters = contextHandler.getServletHandler().getFilters();
        assertThat(filters.length, is(1));
        assertThat(filters[0].getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // After stopping the websocket resources are cleaned up.
        server.stop();
        assertThat(contextHandler.getEventListeners().size(), is(0));
        assertThat(contextHandler.getContainedBeans(JakartaWebSocketServerContainer.class).size(), is(0));
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(0));
        assertNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNull(contextHandler.getServletContext().getAttribute(JakartaWebSocketServerContainer.JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE));
        assertThat(contextHandler.getServletHandler().getFilters().length, is(0));
    }

    private void testEchoMessage() throws Exception
    {
        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        EventSocket socket = new EventSocket();
        try (Session session = client.connectToServer(socket, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }
}
