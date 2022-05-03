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

package org.eclipse.jetty.ee10.websocket.tests.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee10.websocket.api.util.WSURI;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests for conditions due to bad networking.
 */
public class BadNetworkTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setIdleTimeout(Duration.ofMillis(500));
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.setIdleTimeout(Duration.ofSeconds(10));
                factory.setMaxTextMessageSize(1024 * 1024 * 2);
                factory.register(ServerEndpoint.class);
            }
        });
        context.addServlet(holder, "/ws");

        server.setHandler(new HandlerList(context, new DefaultHandler()));
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAbruptClientClose() throws Exception
    {
        CloseTrackingEndpoint wsocket = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> future = client.connect(wsocket, wsUri);

        // Validate that we are connected
        future.get(30, TimeUnit.SECONDS);

        // Have client disconnect abruptly
        Session session = wsocket.getSession();
        session.disconnect();

        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        wsocket.assertReceivedCloseEvent(5000, is(StatusCode.NO_CLOSE), containsString(""));
    }

    @Test
    public void testAbruptServerClose() throws Exception
    {
        CloseTrackingEndpoint wsocket = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> future = client.connect(wsocket, wsUri);

        // Validate that we are connected
        Session session = future.get(30, TimeUnit.SECONDS);

        // Have server disconnect abruptly
        session.getRemote().sendString("abort");

        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        wsocket.assertReceivedCloseEvent(5000, is(StatusCode.NO_CLOSE), containsString(""));
    }

    public static class ServerEndpoint implements WebSocketListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ClientCloseTest.ServerEndpoint.class);
        private Session session;

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        @Override
        public void onWebSocketText(String message)
        {
            try
            {
                if (message.equals("abort"))
                {
                    session.disconnect();
                }
                else
                {
                    // simple echo
                    session.getRemote().sendString(message);
                }
            }
            catch (IOException e)
            {
                LOG.warn("Failed to send string", e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("ServerEndpoint error", cause);
            }
        }
    }
}
