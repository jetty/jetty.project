//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.util.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketFilterTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private ServletContextHandler contextHandler;

    public void start(JettyWebSocketServletContainerInitializer.Configurator configurator) throws Exception
    {
        start(configurator, null);
    }

    public void start(ServletHolder servletHolder) throws Exception
    {
        start(null, servletHolder);
    }

    public void start(JettyWebSocketServletContainerInitializer.Configurator configurator, ServletHolder servletHolder) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        if (servletHolder != null)
            contextHandler.addServlet(servletHolder, "/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, configurator);
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testWebSocketUpgradeFilter() throws Exception
    {
        start((context, container) -> container.addMapping("/", EchoSocket.class));

        // After mapping is added we have an UpgradeFilter.
        assertThat(contextHandler.getServletHandler().getFilters().length, is(1));
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter("WebSocketUpgradeFilter");
        assertNotNull(filterHolder);
        assertThat(filterHolder.getState(), is(AbstractLifeCycle.STARTED));
        assertThat(filterHolder.getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }

    @Test
    public void testLazyWebSocketUpgradeFilter() throws Exception
    {
        start(null, null);

        // JettyWebSocketServerContainer has already been created.
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(contextHandler.getServletContext());
        assertNotNull(container);

        // We should have no WebSocketUpgradeFilter installed because we have added no mappings.
        assertThat(contextHandler.getServletHandler().getFilters().length, is(0));

        // After mapping is added we have an UpgradeFilter.
        container.addMapping("/", EchoSocket.class);
        assertThat(contextHandler.getServletHandler().getFilters().length, is(1));
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter("WebSocketUpgradeFilter");
        assertNotNull(filterHolder);
        assertThat(filterHolder.getState(), is(AbstractLifeCycle.STARTED));
        assertThat(filterHolder.getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }

    @Test
    public void testWebSocketUpgradeFilterWhileStarting() throws Exception
    {
        start(new ServletHolder(new HttpServlet()
        {
            @Override
            public void init()
            {
                JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(getServletContext());
                if (container == null)
                    throw new IllegalArgumentException("Missing JettyWebSocketServerContainer");

                container.addMapping("/", EchoSocket.class);
            }
        }));

        // After mapping is added we have an UpgradeFilter.
        assertThat(contextHandler.getServletHandler().getFilters().length, is(1));
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter("WebSocketUpgradeFilter");
        assertNotNull(filterHolder);
        assertThat(filterHolder.getState(), is(AbstractLifeCycle.STARTED));
        assertThat(filterHolder.getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }
}
