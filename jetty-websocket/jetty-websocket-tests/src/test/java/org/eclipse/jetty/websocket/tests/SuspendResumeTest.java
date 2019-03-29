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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SuspendResumeTest
{
    @WebSocket
    public static class EventSocket
    {
        private static final Logger LOG = Log.getLogger(EventSocket.class);


        BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Session session;

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            LOG.info("onConnect(): " + session);
            this.session = session;
            openLatch.countDown();
        }

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            LOG.info("onMessage(): " + message);
            messages.offer(message);
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            LOG.info("onError(): " + t);
            error.compareAndSet(null, t);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            LOG.info("onClose(): " + statusCode + ":" + reason);
            closeLatch.countDown();
        }
    }

    public class UpgradeServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.setCreator(((req, resp) -> serverSocket));
        }
    }

    private Server server = new Server();
    private WebSocketClient client = new WebSocketClient();
    private EventSocket serverSocket = new EventSocket();
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        contextHandler.addServlet(new ServletHolder(new UpgradeServlet()), "/test");

        JettyWebSocketServletContainerInitializer.configureContext(contextHandler);

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testSuspendResume() throws Exception
    {
        URI uri = new URI("ws://localhost:"+connector.getLocalPort()+"/test");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        // verify connection by sending a message from server to client
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        serverSocket.session.getRemote().sendStringByFuture("verification");
        assertThat(clientSocket.messages.poll(5, TimeUnit.SECONDS), is("verification"));

        // suspend the client so that no read events occur
        SuspendToken suspendToken = clientSocket.session.suspend();

        // verify client can still send messages
        clientSocket.session.getRemote().sendStringByFuture("message-from-client");
        assertThat(serverSocket.messages.poll(5, TimeUnit.SECONDS), is("message-from-client"));

        // the first message is received as we had already demanded before suspend
        serverSocket.session.getRemote().sendStringByFuture("first-message");
        assertThat(clientSocket.messages.poll(5, TimeUnit.SECONDS), is("first-message"));

        // the second message is not received as it is suspended
        serverSocket.session.getRemote().sendStringByFuture("second-message");
        assertNull(clientSocket.messages.poll(2, TimeUnit.SECONDS));

        // client should receive message after it resumes
        suspendToken.resume();
        assertThat(clientSocket.messages.poll(5, TimeUnit.SECONDS), is("second-message"));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check no errors occurred
        assertNull(clientSocket.error.get());
        assertNull(serverSocket.error.get());
    }
}
