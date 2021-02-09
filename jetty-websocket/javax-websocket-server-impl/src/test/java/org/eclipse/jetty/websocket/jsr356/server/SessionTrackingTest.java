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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class SessionTrackingTest
{
    private Server server;
    private ServerContainer serverContainer;
    private WebSocketServerFactory wsServerFactory;
    private URI serverURI;

    @BeforeEach
    public void startServer() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        ServerConnector serverConnector = new ServerConnector(server);
        serverConnector.setPort(0);
        server.addConnector(serverConnector);
        ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        servletContextHandler.setContextPath("/");
        server.setHandler(servletContextHandler);

        serverContainer = WebSocketServerContainerInitializer.configureContext(servletContextHandler);
        serverContainer.addEndpoint(EchoSocket.class);

        wsServerFactory = serverContainer.getWebSocketServerFactory();

        server.start();

        serverURI = new URI("ws://localhost:" + serverConnector.getLocalPort());
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testAddRemoveSessions() throws Exception
    {
        // Create Client
        ClientContainer clientContainer = new ClientContainer();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientContainer.getClient().setExecutor(clientThreads);
        try
        {
            CountDownLatch openedLatch = new CountDownLatch(2);
            CountDownLatch closedLatch = new CountDownLatch(2);
            wsServerFactory.addSessionListener(new WebSocketSessionListener()
            {
                @Override
                public void onSessionOpened(WebSocketSession session)
                {
                    openedLatch.countDown();
                }

                @Override
                public void onSessionClosed(WebSocketSession session)
                {
                    closedLatch.countDown();
                }
            });

            clientContainer.start();

            // Establish connections
            ClientSocket cli1 = new ClientSocket();
            clientContainer.connectToServer(cli1, serverURI.resolve("/test"));
            cli1.waitForOpen(1, TimeUnit.SECONDS);

            // Establish new connection
            ClientSocket cli2 = new ClientSocket();
            clientContainer.connectToServer(cli2, serverURI.resolve("/test"));
            cli2.waitForOpen(1, TimeUnit.SECONDS);

            openedLatch.await(5, TimeUnit.SECONDS);
            assertServerOpenConnectionCount(2);

            // Establish close both connections
            cli1.session.close();
            cli2.session.close();

            cli1.waitForClose(1, TimeUnit.SECONDS);
            cli2.waitForClose(1, TimeUnit.SECONDS);

            closedLatch.await(5, TimeUnit.SECONDS);
            assertServerOpenConnectionCount(0);
        }
        finally
        {
            clientContainer.stop();
        }
    }

    private void assertServerOpenConnectionCount(int expectedCount)
    {
        Set<Session> sessions = serverContainer.getOpenSessions();
        int openCount = 0;
        for (Session session : sessions)
        {
            assertThat("Session.isopen: " + session, session.isOpen(), Matchers.is(true));
            openCount++;
        }
        assertThat("Open Session Count", openCount, Matchers.is(expectedCount));
    }

    private static class ClientSocket extends Endpoint
    {
        private Session session;
        private CountDownLatch openLatch = new CountDownLatch(1);
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            openLatch.countDown();
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            closeLatch.countDown();
        }

        public void waitForOpen(long timeout, TimeUnit unit) throws InterruptedException
        {
            assertThat("ClientSocket opened", openLatch.await(timeout, unit), Matchers.is(true));
        }

        public void waitForClose(long timeout, TimeUnit unit) throws InterruptedException
        {
            assertThat("ClientSocket opened", closeLatch.await(timeout, unit), Matchers.is(true));
        }
    }

    @ServerEndpoint("/test")
    public static class EchoSocket
    {
        @OnMessage
        public String echo(String msg)
        {
            return msg;
        }
    }
}
