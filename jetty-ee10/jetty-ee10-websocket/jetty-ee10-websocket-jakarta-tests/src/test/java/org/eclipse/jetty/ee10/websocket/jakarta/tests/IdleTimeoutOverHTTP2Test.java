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

import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IdleTimeoutOverHTTP2Test
{
    private Server server;
    private ServerConnector connector;
    private HTTP2CServerConnectionFactory http2Server;
    private HTTP2Client http2Client;
    private JakartaWebSocketClientContainer wsClient;

    private void startServer(JakartaWebSocketServletContainerInitializer.Configurator configurator) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        http2Server = new HTTP2CServerConnectionFactory(httpConfig);
        connector = new ServerConnector(server, 1, 1, http2Server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);
        JakartaWebSocketServletContainerInitializer.configure(context, configurator);

        server.start();
    }

    private void startClient() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        http2Client = new HTTP2Client(clientConnector);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        wsClient = new JakartaWebSocketClientContainer(httpClient);
        wsClient.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testIdleTimeoutConfiguredByWebSocketEndPoint() throws Exception
    {
        startServer((context, container) -> container.addEndpoint(ConfigureIdleTimeoutAnnotatedEndPoint.class));
        startClient();

        EventSocket clientEndpoint = new EventSocket();
        try (Session ignored = wsClient.connectToServer(clientEndpoint, URI.create("ws://localhost:" + connector.getLocalPort() + ConfigureIdleTimeoutAnnotatedEndPoint.PATH)))
        {
            assertTrue(clientEndpoint.closeLatch.await(2 * ConfigureIdleTimeoutAnnotatedEndPoint.IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testIdleTimeoutConfiguredByServerContainerDisabledByWebSocketEndPoint() throws Exception
    {
        long idleTimeout = 1000;
        startServer((context, container) ->
        {
            container.setDefaultMaxSessionIdleTimeout(idleTimeout);
            container.addEndpoint(DisableIdleTimeoutAnnotatedEndPoint.class);
        });
        startClient();

        EventSocket clientEndpoint = new EventSocket();
        try (Session ignored = wsClient.connectToServer(clientEndpoint, URI.create("ws://localhost:" + connector.getLocalPort() + DisableIdleTimeoutAnnotatedEndPoint.PATH)))
        {
            assertFalse(clientEndpoint.closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testIdleTimeoutConfiguredExternally() throws Exception
    {
        long idleTimeout = 1000;
        startServer((context, container) ->
            container.addEndpoint(ExternalIdleTimeoutAnnotatedEndPoint.class));
        // Configure the idle timeout here, WebSocket should inherit it.
        http2Server.setStreamIdleTimeout(idleTimeout);
        startClient();

        EventSocket clientEndpoint = new EventSocket();
        try (Session ignored = wsClient.connectToServer(clientEndpoint, URI.create("ws://localhost:" + connector.getLocalPort() + ExternalIdleTimeoutAnnotatedEndPoint.PATH)))
        {
            assertTrue(clientEndpoint.closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        }
    }

    @ServerEndpoint(ConfigureIdleTimeoutAnnotatedEndPoint.PATH)
    public static class ConfigureIdleTimeoutAnnotatedEndPoint
    {
        public static final String PATH = "/configureIdleTimeout";
        public static final long IDLE_TIMEOUT = 1000;

        @OnOpen
        public void onOpen(Session session)
        {
            session.setMaxIdleTimeout(IDLE_TIMEOUT);
        }

        @OnError
        public void onError(Throwable failure)
        {
            // Just to reduce WARN logging in tests.
        }
    }

    @ServerEndpoint(DisableIdleTimeoutAnnotatedEndPoint.PATH)
    public static class DisableIdleTimeoutAnnotatedEndPoint
    {
        public static final String PATH = "/disableIdleTimeout";

        @OnOpen
        public void onOpen(Session session)
        {
            session.setMaxIdleTimeout(-1);
        }
    }

    @ServerEndpoint(ExternalIdleTimeoutAnnotatedEndPoint.PATH)
    public static class ExternalIdleTimeoutAnnotatedEndPoint
    {
        public static final String PATH = "/externalIdleTimeout";

        @OnError
        public void onError(Throwable failure)
        {
            // Just to reduce WARN logging in tests.
        }
    }
}
