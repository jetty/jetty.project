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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.common.WebSocketSession;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcurrentConnectTest
{
    private static final int MAX_CONNECTIONS = 150;

    private Server server;
    private WebSocketClient client;
    private URI uri;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        JettyWebSocketServlet servlet = new JettyWebSocketServlet()
        {
            @Override
            protected void configure(JettyWebSocketServletFactory factory)
            {
                factory.register(EchoSocket.class);
            }
        };

        context.addServlet(new ServletHolder(servlet), "/");
        server.setHandler(context);
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.start();
        uri = new URI("ws://localhost:" + connector.getLocalPort());

        client = new WebSocketClient();
        client.getHttpClient().setMaxConnectionsPerDestination(MAX_CONNECTIONS);
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testConcurrentConnect() throws Exception
    {
        List<EventSocket> listeners = new ArrayList();
        CloseListener closeListener = new CloseListener();
        client.addSessionListener(closeListener);
        final int messages = MAX_CONNECTIONS;

        for (int i = 0; i < messages; i++)
        {
            try
            {
                EventSocket wsListener = new EventSocket();
                listeners.add(wsListener);
                client.connect(wsListener, uri);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        for (EventSocket l : listeners)
        {
            assertTrue(l.openLatch.await(5, TimeUnit.SECONDS));
        }

        for (EventSocket l : listeners)
        {
            l.session.getRemote().sendString("ping");
            assertThat(l.textMessages.poll(5, TimeUnit.SECONDS), is("ping"));
            l.session.close(StatusCode.NORMAL, "close from client");
        }

        for (EventSocket l : listeners)
        {
            assertTrue(l.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(l.closeCode, is(StatusCode.NORMAL));
            assertThat(l.closeReason, is("close from client"));
            assertNull(l.error);
        }

        closeListener.closeLatch.await(5, TimeUnit.SECONDS);
        assertTrue(client.getOpenSessions().isEmpty());
        assertTrue(client.getContainedBeans(WebSocketSession.class).isEmpty());
    }

    public static class CloseListener implements WebSocketSessionListener
    {
        public CountDownLatch closeLatch = new CountDownLatch(MAX_CONNECTIONS);

        @Override
        public void onWebSocketSessionClosed(Session session)
        {
            closeLatch.countDown();
        }
    }
}
