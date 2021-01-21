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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SuspendResumeTest
{
    @WebSocket
    public static class SuspendSocket extends EventSocket
    {
        volatile SuspendToken suspendToken = null;

        @Override
        public void onMessage(String message) throws IOException
        {
            if ("suspend".equals(message))
                suspendToken = session.suspend();
            super.onMessage(message);
        }

        @Override
        public void onError(Throwable cause)
        {
            super.onError(cause);
            cause.printStackTrace();
        }
    }

    public class UpgradeServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(((req, resp) -> serverSocket));
        }
    }

    private Server server = new Server();
    private WebSocketClient client = new WebSocketClient();
    private SuspendSocket serverSocket = new SuspendSocket();
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        contextHandler.addServlet(new ServletHolder(new UpgradeServlet()), "/suspend");

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
    public void testSuspendWhenProcessingFrame() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        clientSocket.session.getRemote().sendString("suspend");
        clientSocket.session.getRemote().sendString("suspend");
        clientSocket.session.getRemote().sendString("hello world");

        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.suspendToken.resume();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.suspendToken.resume();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check we closed normally
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));
    }

    @Test
    public void testExternalSuspend() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        // verify connection by sending a message from server to client
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        serverSocket.session.getRemote().sendString("verification");
        assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is("verification"));

        // suspend the client so that no read events occur
        SuspendToken suspendToken = clientSocket.session.suspend();

        // verify client can still send messages
        clientSocket.session.getRemote().sendString("message-from-client");
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("message-from-client"));

        // the message is not received as it is suspended
        serverSocket.session.getRemote().sendString("message-from-server");
        assertNull(clientSocket.textMessages.poll(2, TimeUnit.SECONDS));

        // client should receive message after it resumes
        suspendToken.resume();
        assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is("message-from-server"));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check we closed normally
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));
    }

    @Test
    public void testSuspendAfterClose() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        // verify connection by sending a message from server to client
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        serverSocket.session.getRemote().sendString("verification");
        assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is("verification"));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check we closed normally
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));

        // suspend after closed throws ISE
        assertThrows(IllegalStateException.class, () -> clientSocket.session.suspend());
    }
}
