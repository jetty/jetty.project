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

package org.eclipse.jetty.ee11.websocket.tests;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee11.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee11.websocket.servlet.WebSocketUpgradeFilter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketRestartTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
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
    public void testWebSocketRestart() throws Exception
    {
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addMapping("/", EchoSocket.class));
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
        assertThat(contextHandler.getContainedBeans(JettyWebSocketServerContainer.class).size(), is(1));
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(1));
        assertNotNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNotNull(contextHandler.getServletContext().getAttribute(JettyWebSocketServerContainer.JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE));

        // We have one filter, and it is a WebSocketUpgradeFilter.
        FilterHolder[] filters = contextHandler.getServletHandler().getFilters();
        assertThat(filters.length, is(1));
        assertThat(filters[0].getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // After stopping the websocket resources are cleaned up.
        server.stop();
        assertThat(contextHandler.getEventListeners().size(), is(2));
        assertThat(contextHandler.getContainedBeans(JettyWebSocketServerContainer.class).size(), is(1));
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(1));
        assertNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNull(contextHandler.getServletContext().getAttribute(JettyWebSocketServerContainer.JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE));
        assertThat(contextHandler.getServletHandler().getFilters().length, is(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testContextRestart(boolean earlyInitWebSocketComponents) throws Exception
    {
        if (earlyInitWebSocketComponents)
            WebSocketServerComponents.ensureWebSocketComponents(server, contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addMapping("/", EchoSocket.class));
        server.start();
        WebSocketComponents components = WebSocketServerComponents.getWebSocketComponents(contextHandler);

        int initialNumEventListeners = contextHandler.getEventListeners().size();
        for (int i = 0; i < 100; i++)
        {
            assertThat(contextHandler.getEventListeners().size(), is(initialNumEventListeners));
            assertThat(contextHandler.getContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE), sameInstance(components));
            assertThat(contextHandler.getBean(WebSocketServerComponents.class), sameInstance(components));

            contextHandler.stop();

            // The CompressionPools should still be running because they are a resource managed by the server.
            assertTrue(components.getDeflaterPool().isRunning());
            assertTrue(components.getInflaterPool().isRunning());

            // Even though the components is now stopped the executor is still running as it has been taken from the server.
            assertTrue(components.isStopped());
            assertThat(components.getExecutor(), instanceOf(QueuedThreadPool.class));
            assertTrue(((QueuedThreadPool)components.getExecutor()).isRunning());

            // The components now persists as a bean though restarts.
            assertNull(contextHandler.getContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
            assertThat(contextHandler.getBean(WebSocketServerComponents.class), sameInstance(components));

            contextHandler.start();
            testEchoMessage();
        }

        // Verify we have not accumulated websocket resources by restarting.
        assertThat(contextHandler.getEventListeners().size(), is(initialNumEventListeners));
        assertThat(contextHandler.getContainedBeans(JettyWebSocketServerContainer.class).size(), is(1));
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(1));
        assertNotNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNotNull(contextHandler.getServletContext().getAttribute(JettyWebSocketServerContainer.JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE));

        // We have one filter, and it is a WebSocketUpgradeFilter.
        FilterHolder[] filters = contextHandler.getServletHandler().getFilters();
        assertThat(filters.length, is(1));
        assertThat(filters[0].getFilter(), instanceOf(WebSocketUpgradeFilter.class));

        // Verify the state after stopping the server.
        contextHandler.stop();
        assertThat(contextHandler.getEventListeners().size(), is(2));

        // Server managed components should still be running.
        assertThat(contextHandler.getContainedBeans(WebSocketServerComponents.class).size(), is(1));
        assertThat(contextHandler.getBean(WebSocketServerComponents.class), sameInstance(components));
        assertTrue(components.getInflaterPool().isRunning());
        assertTrue(components.getDeflaterPool().isRunning());
        assertTrue(((QueuedThreadPool)components.getExecutor()).isRunning());

        // The other components should now be stopped.
        assertThat(contextHandler.getContainedBeans(JettyWebSocketServerContainer.class).size(), is(1));
        assertThat(contextHandler.getContainedBeans(WebSocketMappings.class).size(), is(1));
        assertTrue(contextHandler.getBean(JettyWebSocketServerContainer.class).isStopped());
        assertTrue(components.isStopped());

        // Attributes should be removed.
        assertNull(contextHandler.getServletContext().getAttribute(WebSocketServerComponents.WEBSOCKET_COMPONENTS_ATTRIBUTE));
        assertNull(contextHandler.getServletContext().getAttribute(JettyWebSocketServerContainer.JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE));

        // The WebSocketUpgradeFilter should be removed.
        assertThat(contextHandler.getServletHandler().getFilters().length, is(0));
    }

    private void testEchoMessage() throws Exception
    {
        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.sendText("hello world", Callback.NOOP);
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }
}
