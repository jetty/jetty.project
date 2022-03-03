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

package org.eclipse.jetty.ee9.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.servlet.WebSocketUpgradeFilter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketFilterTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void before()
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        client = new WebSocketClient();
    }

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
        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        if (servletHolder != null)
            contextHandler.addServlet(servletHolder, "/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, configurator);

        server.start();
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
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter(WebSocketUpgradeFilter.class.getName());
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
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter(WebSocketUpgradeFilter.class.getName());
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
        FilterHolder filterHolder = contextHandler.getServletHandler().getFilter(WebSocketUpgradeFilter.class.getName());
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
    public void testMultipleWebSocketUpgradeFilter() throws Exception
    {
        String idleTimeoutFilter1 = "4999";
        String idleTimeoutFilter2 = "3999";
        start((context, container) ->
        {
            ServletContextHandler contextHandler = Objects.requireNonNull(ServletContextHandler.getServletContextHandler(context));

            // This filter replaces the default filter as we use the pre-defined name.
            FilterHolder filterHolder = new FilterHolder(WebSocketUpgradeFilter.class);
            filterHolder.setName(WebSocketUpgradeFilter.class.getName());
            filterHolder.setInitParameter("idleTimeout", idleTimeoutFilter1);
            contextHandler.addFilter(filterHolder, "/primaryFilter/*", EnumSet.of(DispatcherType.REQUEST));

            // This is an additional filter.
            filterHolder = new FilterHolder(WebSocketUpgradeFilter.class);
            filterHolder.setName("Secondary Upgrade Filter");
            filterHolder.setInitParameter("idleTimeout", idleTimeoutFilter2);
            contextHandler.addFilter(filterHolder, "/secondaryFilter/*", EnumSet.of(DispatcherType.REQUEST));

            // Add mappings to the server container (same WebSocketMappings is referenced by both upgrade filters).
            container.addMapping("/echo", EchoSocket.class);
            container.addMapping("/primaryFilter/echo", LowerCaseEchoSocket.class);
            container.addMapping("/secondaryFilter/echo", UpperCaseEchoSocket.class);
        });

        // Verify we have manually added 2 WebSocketUpgrade Filters.
        List<FilterHolder> upgradeFilters = Arrays.stream(contextHandler.getServletHandler().getFilters())
            .filter(holder -> holder.getFilter() instanceof WebSocketUpgradeFilter)
            .collect(Collectors.toList());
        assertThat(contextHandler.getServletHandler().getFilters().length, is(2));
        assertThat(upgradeFilters.size(), is(2));
        for (FilterHolder filterHolder : upgradeFilters)
        {
            assertThat(filterHolder.getState(), is(AbstractLifeCycle.STARTED));
            assertThat(filterHolder.getFilter(), instanceOf(WebSocketUpgradeFilter.class));
        }

        // The /echo path should not match either of the upgrade filters even though it has a valid mapping, we get 404 response.
        URI firstUri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        ExecutionException error = assertThrows(ExecutionException.class, () -> client.connect(new EventSocket(), firstUri).get(5, TimeUnit.SECONDS));
        assertThat(error.getMessage(), containsString("404 Not Found"));

        // The /primaryFilter/echo path should convert to lower case and have idleTimeout configured on the first upgradeFilter.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/primaryFilter/echo");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hElLo wOrLd");
            session.getRemote().sendString("getIdleTimeout");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.textMessages.poll(), is("hello world"));
        assertThat(socket.textMessages.poll(), is(idleTimeoutFilter1));

        // The /secondaryFilter/echo path should convert to upper case and have idleTimeout configured on the second upgradeFilter.
        uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/secondaryFilter/echo");
        socket = new EventSocket();
        connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hElLo wOrLd");
            session.getRemote().sendString("getIdleTimeout");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.textMessages.poll(), is("HELLO WORLD"));
        assertThat(socket.textMessages.poll(), is(idleTimeoutFilter2));
    }

    @Test
    public void testCustomUpgradeFilter() throws Exception
    {
        start((context, container) ->
        {
            ServletContextHandler contextHandler = Objects.requireNonNull(ServletContextHandler.getServletContextHandler(context));

            // This custom filter replaces the default filter as we use the pre-defined name, and adds mapping in init().
            FilterHolder filterHolder = new FilterHolder(MyUpgradeFilter.class);
            filterHolder.setName(WebSocketUpgradeFilter.class.getName());
            contextHandler.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
        });

        FilterHolder[] holders = contextHandler.getServletHandler().getFilters();
        assertThat(holders.length, is(1));
        assertThat(holders[0].getFilter(), instanceOf(MyUpgradeFilter.class));

        // We can reach the echo endpoint and get correct response.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hElLo wOrLd");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.textMessages.poll(), is("hElLo wOrLd"));
    }

    @Test
    public void testDefaultWebSocketUpgradeFilterOrdering() throws Exception
    {
        String defaultIdleTimeout = Long.toString(WebSocketConstants.DEFAULT_IDLE_TIMEOUT.toMillis());
        JettyWebSocketWebApp webApp = new JettyWebSocketWebApp("wsuf-ordering1");
        Path webXml = MavenTestingUtils.getTestResourcePath("wsuf-ordering1.xml");
        webApp.copyWebXml(webXml);
        webApp.copyClass(WebSocketEchoServletContextListener.class);
        webApp.copyClass(WebSocketEchoServletContextListener.EchoSocket.class);

        server.setHandler(webApp);
        server.start();
        client.start();

        // We have both websocket upgrade filters installed.
        FilterHolder[] filterHolders = webApp.getServletHandler().getFilters();
        assertThat(filterHolders.length, is(2));
        assertThat(filterHolders[0].getFilter(), instanceOf(WebSocketUpgradeFilter.class));
        assertThat(filterHolders[1].getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // The custom filter defined in web.xml should be first in line so it will do the upgrade.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + webApp.getContextPath() + "/echo");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
            session.getRemote().sendString("getIdleTimeout");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.textMessages.poll(), is("hello world"));
        assertThat(socket.textMessages.poll(), is(defaultIdleTimeout));
    }

    @Test
    public void testWebSocketUpgradeFilterOrdering() throws Exception
    {
        String timeoutFromAltFilter = "5999";
        JettyWebSocketWebApp webApp = new JettyWebSocketWebApp("wsuf-ordering2");
        Path webXml = MavenTestingUtils.getTestResourcePath("wsuf-ordering2.xml");
        webApp.copyWebXml(webXml);
        webApp.copyClass(WebSocketEchoServletContextListener.class);
        webApp.copyClass(WebSocketEchoServletContextListener.EchoSocket.class);

        server.setHandler(webApp);
        server.start();
        client.start();

        // We have both websocket upgrade filters installed.
        FilterHolder[] filterHolders = webApp.getServletHandler().getFilters();
        assertThat(filterHolders.length, is(2));
        assertThat(filterHolders[0].getFilter(), instanceOf(WebSocketUpgradeFilter.class));
        assertThat(filterHolders[1].getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // The custom filter defined in web.xml should be first in line so it will do the upgrade.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + webApp.getContextPath() + "/echo");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
            session.getRemote().sendString("getIdleTimeout");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.textMessages.poll(), is("hello world"));
        assertThat(socket.textMessages.poll(), is(timeoutFromAltFilter));
    }

    @WebListener
    public static class WebSocketEchoServletContextListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(sce.getServletContext());
            container.addMapping("/echo", EchoSocket.class);
        }

        @WebSocket
        public static class EchoSocket
        {
            @OnWebSocketMessage
            public void onMessage(Session session, String message) throws IOException
            {
                if ("getIdleTimeout".equals(message))
                    session.getRemote().sendString(Long.toString(session.getIdleTimeout().toMillis()));
                else
                    session.getRemote().sendString(message);
            }
        }
    }

    public static class MyUpgradeFilter extends WebSocketUpgradeFilter
    {
        @Override
        public void init(FilterConfig config) throws ServletException
        {
            JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(config.getServletContext());
            container.addMapping("/echo", EchoSocket.class);
            super.init(config);
        }
    }

    @WebSocket
    public static class LowerCaseEchoSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws IOException
        {
            if ("getIdleTimeout".equals(message))
                session.getRemote().sendString(Long.toString(session.getIdleTimeout().toMillis()));
            else
                session.getRemote().sendString(message.toLowerCase());
        }
    }

    @WebSocket
    public static class UpperCaseEchoSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws IOException
        {
            if ("getIdleTimeout".equals(message))
                session.getRemote().sendString(Long.toString(session.getIdleTimeout().toMillis()));
            else
                session.getRemote().sendString(message.toUpperCase());
        }
    }
}
