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

package org.eclipse.jetty.ee9.websocket.tests.client;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.ee9.websocket.tests.EchoCreator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
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
        ServletHolder holder = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.setIdleTimeout(Duration.ofSeconds(10));
                factory.setMaxTextMessageSize(1024 * 1024 * 2);
                factory.setCreator(new EchoCreator());
            }
        });
        context.addServlet(holder, "/ws");

        server.setHandler(new HandlerList(context, new DefaultHandler()));
        JettyWebSocketServletContainerInitializer.configure(context, null);

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
            public void onWebSocketSessionClosed(Session session)
            {
                onSessionCloseLatch.countDown();
            }
        });

        client.start();
        try
        {
            CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();
            client.setIdleTimeout(Duration.ofSeconds(10));

            URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("echo");
            Future<Session> future = client.connect(cliSock, wsUri, request);

            try (Session sess = future.get(30000, TimeUnit.MILLISECONDS))
            {
                assertThat("Session", sess, notNullValue());
                assertThat("Session.open", sess.isOpen(), is(true));
                assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
                assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

                Collection<Session> sessions = client.getOpenSessions();
                assertThat("client.connectionManager.sessions.size", sessions.size(), is(1));

                RemoteEndpoint remote = sess.getRemote();
                remote.sendString("Hello World!");

                Collection<Session> open = client.getOpenSessions();
                assertThat("(Before Close) Open Sessions.size", open.size(), is(1));

                String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Message", received, containsString("Hello World!"));

                sess.close(StatusCode.NORMAL, null);
            }

            cliSock.assertReceivedCloseEvent(30000, is(StatusCode.NORMAL));

            assertTrue(onSessionCloseLatch.await(5, TimeUnit.SECONDS), "Saw onSessionClose events");
            TimeUnit.SECONDS.sleep(1);

            Collection<Session> open = client.getOpenSessions();
            assertThat("(After Close) Open Sessions.size", open.size(), is(0));
        }
        finally
        {
            client.stop();
        }
    }
}
