//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for conditions due to bad networking.
 */
public class BadNetworkTest
{
    private static final Logger LOG = Log.getLogger(BadNetworkTest.class);
    private Server server;
    private WebSocketClient client;
    private ServletContextHandler context;
    private ServerConnector connector;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getPolicy().setIdleTimeout(500);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server);
        server.addConnector(connector);

        context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder holder = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(100000);
                factory.getPolicy().setMaxTextMessageSize(1024 * 1024 * 2);
                factory.setCreator((req, resp) -> new ServerEndpoint());
            }
        });
        context.addServlet(holder, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

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
        AtomicReference<WebSocketSession> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        connector.addBean(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
            }

            @Override
            public void onClosed(Connection connection)
            {
                serverCloseLatch.countDown();
            }
        });
        CountDownLatch sessionCloseLatch = new CountDownLatch(1);
        WebSocketServerFactory wssf = (WebSocketServerFactory)context.getServletContext().getAttribute(WebSocketServletFactory.class.getName());
        wssf.addSessionListener(new WebSocketSessionListener()
        {
            @Override
            public void onSessionOpened(WebSocketSession session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public void onSessionClosed(WebSocketSession session)
            {
                sessionCloseLatch.countDown();
            }
        });

        CloseTrackingEndpoint wsocket = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> future = client.connect(wsocket, wsUri);

        // Validate that we are connected
        future.get(30, TimeUnit.SECONDS);

        WebSocketSession serverSession = serverSessionRef.get();

        // Have client disconnect abruptly
        Session session = wsocket.getSession();
        LOG.info("client.disconnect");
        session.disconnect();

        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        wsocket.assertReceivedCloseEvent(5000, is(StatusCode.NO_CLOSE), containsString(""));

        assertTrue(serverCloseLatch.await(1, TimeUnit.SECONDS), "Server Connection Close should have happened");
        assertTrue(sessionCloseLatch.await(1, TimeUnit.SECONDS), "Server Session Close should have happened");

        AbstractWebSocketConnection conn = (AbstractWebSocketConnection)serverSession.getConnection();
        assertThat("Connection.isOpen", conn.isOpen(), is(false));
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
        private static final Logger LOG = Log.getLogger(ClientCloseTest.ServerEndpoint.class);
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
                LOG.warn(e);
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
                LOG.debug(cause);
            }
        }
    }
}
