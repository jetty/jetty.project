//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
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

        JettyWebSocketServlet servlet = new JettyWebSocketServlet() {
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
            assertThat(l.messageQueue.poll(5, TimeUnit.SECONDS), is("ping"));
            l.session.close(StatusCode.NORMAL, "close from client");
        }

        for (EventSocket l : listeners)
        {
            assertTrue(l.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(l.statusCode, is(StatusCode.NORMAL));
            assertThat(l.reason, is("close from client"));
            assertNull(l.error);
        }

        closeListener.closeLatch.await(5, TimeUnit.SECONDS);
        for (EventSocket l : listeners)
        {
            assertTrue(((WebSocketSession)l.session).isStopped());
        }

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
