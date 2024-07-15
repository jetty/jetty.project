//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.LogarithmicArrayByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }

    public class UpgradeServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.setCreator(((req, resp) -> serverSocket));
        }
    }

    private Server server;
    private WebSocketClient client;
    private SuspendSocket serverSocket;
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        server.addBean(new LogarithmicArrayByteBufferPool(-1, -1, -1, 0, 0, 0, 0));
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        contextHandler.addServlet(new ServletHolder(new UpgradeServlet()), "/suspend");
        serverSocket = new SuspendSocket();

        JettyWebSocketServletContainerInitializer.configure(contextHandler, null);

        server.start();
        client = new WebSocketClient();
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

        // check no errors occurred
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);
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

        // check no errors occurred
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);
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

        // check no errors occurred
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);

        // suspend after closed throws ISE
        assertThrows(IllegalStateException.class, () -> clientSocket.session.suspend());
    }

    @Test
    public void testTimeoutWhileSuspended() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Set short idleTimeout on server.
        int idleTimeout = 1000;
        serverSocket.session.setIdleTimeout(Duration.ofMillis(idleTimeout));

        // Suspend on the server.
        clientSocket.session.getRemote().sendString("suspend");
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));

        // Send two messages, with batching on, so they are read into same network buffer on the server.
        // First frame is read and delayed inside the JettyWebSocketFrameHandler suspendState, second frame remains in the network buffer.
        clientSocket.session.getRemote().setBatchMode(BatchMode.ON);
        clientSocket.session.getRemote().sendString("no demand");
        clientSocket.session.getRemote().sendString("this should sit in network buffer");
        clientSocket.session.getRemote().flush();
        assertNotNull(serverSocket.suspendToken);

        // Make sure both sides are closed.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // We received no additional messages.
        assertNull(serverSocket.textMessages.poll());
        assertNull(serverSocket.binaryMessages.poll());

        // Check the idleTimeout occurred.
        assertThat(serverSocket.error, instanceOf(WebSocketTimeoutException.class));
        assertNull(clientSocket.error);
        assertThat(clientSocket.closeCode, equalTo(StatusCode.SHUTDOWN));
        assertThat(clientSocket.closeReason, equalTo("Connection Idle Timeout"));

        // We should have no used buffers in the pool.
        ArrayRetainableByteBufferPool pool = (ArrayRetainableByteBufferPool)connector.getByteBufferPool().asRetainableByteBufferPool();
        assertThat(pool.getHeapByteBufferCount(), equalTo(pool.getAvailableHeapByteBufferCount()));
        assertThat(pool.getDirectByteBufferCount(), equalTo(pool.getAvailableDirectByteBufferCount()));
    }
}
