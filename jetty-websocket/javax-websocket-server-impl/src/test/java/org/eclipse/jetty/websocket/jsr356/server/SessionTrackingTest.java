//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.Collection;
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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionTrackingTest
{
    public static class ClientSocket extends Endpoint
    {
        public Session session;
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);

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
            assertThat("ClientSocket opened",openLatch.await(timeout,unit),is(true));
        }

        public void waitForClose(long timeout, TimeUnit unit) throws InterruptedException
        {
            assertThat("ClientSocket opened",closeLatch.await(timeout,unit),is(true));
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

    private static Server server;
    private static WebSocketServerFactory wsServerFactory;
    private static URI serverURI;

    @BeforeClass
    public static void startServer() throws Exception
    {
        Server server = new Server();
        ServerConnector serverConnector = new ServerConnector(server);
        serverConnector.setPort(0);
        server.addConnector(serverConnector);
        ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        servletContextHandler.setContextPath("/");
        server.setHandler(servletContextHandler);

        ServerContainer serverContainer = WebSocketServerContainerInitializer.configureContext(servletContextHandler);
        serverContainer.addEndpoint(EchoSocket.class);

        wsServerFactory = serverContainer.getWebSocketServerFactory();

        server.start();

        String host = serverConnector.getHost();
        if (StringUtil.isBlank(host))
        {
            host = "localhost";
        }
        serverURI = new URI("ws://" + host + ":" + serverConnector.getLocalPort());
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        if (server == null)
        {
            return;
        }

        server.stop();
    }

    @Test
    public void testAddRemoveSessions() throws Exception
    {
        // Create Client
        ClientContainer clientContainer = new ClientContainer();
        try
        {
            clientContainer.start();

            // Establish connections
            ClientSocket cli1 = new ClientSocket();
            clientContainer.connectToServer(cli1,serverURI.resolve("/test"));
            cli1.waitForOpen(1,TimeUnit.SECONDS);

            // Assert open connections
            assertServerOpenConnectionCount(1);

            // Establish new connection
            ClientSocket cli2 = new ClientSocket();
            clientContainer.connectToServer(cli2,serverURI.resolve("/test"));
            cli2.waitForOpen(1,TimeUnit.SECONDS);

            // Assert open connections
            assertServerOpenConnectionCount(2);

            // Establish close both connections
            cli1.session.close();
            cli2.session.close();

            cli1.waitForClose(1,TimeUnit.SECONDS);
            cli2.waitForClose(1,TimeUnit.SECONDS);

            // Assert open connections
            assertServerOpenConnectionCount(0);
        }
        finally
        {
            clientContainer.stop();
        }
    }

    private void assertServerOpenConnectionCount(int expectedCount)
    {
        Collection<javax.websocket.Session> sessions = wsServerFactory.getBeans(javax.websocket.Session.class);
        int openCount = 0;
        for (javax.websocket.Session session : sessions)
        {
            assertThat("Session.isopen: " + session,session.isOpen(),is(true));
            openCount++;
        }
        assertThat("Open Session Count",openCount,is(expectedCount));
    }
}
