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

import java.net.URI;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientSessionsTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(10000);
                factory.getPolicy().setMaxTextMessageSize(1024 * 1024 * 2);
                factory.register(EchoSocket.class);
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
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testBasicEchoFromClient() throws Exception
    {
        WebSocketClient client = new WebSocketClient();

        CountDownLatch onSessionCloseLatch = new CountDownLatch(1);

        client.addSessionListener(new WebSocketSessionListener()
        {
            @Override
            public void onSessionClosed(WebSocketSession session)
            {
                onSessionCloseLatch.countDown();
            }
        });

        client.start();
        try
        {
            CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();
            client.getPolicy().setIdleTimeout(10000);

            URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("echo");
            Future<Session> future = client.connect(cliSock, wsUri, request);
            Session sess = null;

            try
            {
                sess = future.get(30000, TimeUnit.MILLISECONDS);
                assertThat("Session", sess, notNullValue());
                assertThat("Session.open", sess.isOpen(), is(true));
                assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
                assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

                Collection<WebSocketSession> sessions = client.getOpenSessions();
                assertThat("client.connectionManager.sessions.size", sessions.size(), is(1));

                RemoteEndpoint remote = sess.getRemote();
                remote.sendString("Hello World!");

                Set<WebSocketSession> open = client.getOpenSessions();
                assertThat("(Before Close) Open Sessions.size", open.size(), is(1));

                String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Message", received, containsString("Hello World!"));
            }
            finally
            {
                cliSock.getSession().close();
            }

            cliSock.assertReceivedCloseEvent(30000, is(StatusCode.NORMAL));

            assertTrue(onSessionCloseLatch.await(5, TimeUnit.SECONDS), "Saw onSessionClose events");
            TimeUnit.SECONDS.sleep(1);

            Set<WebSocketSession> open = client.getOpenSessions();
            assertThat("(After Close) Open Sessions.size", open.size(), is(0));
        }
        finally
        {
            client.stop();
        }
    }
}
