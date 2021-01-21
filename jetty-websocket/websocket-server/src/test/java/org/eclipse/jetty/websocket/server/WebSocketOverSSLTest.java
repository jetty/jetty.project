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

package org.eclipse.jetty.websocket.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.helper.CaptureSocket;
import org.eclipse.jetty.websocket.server.helper.SessionServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketOverSSLTest
{
    public static final int CONNECT_TIMEOUT = 15000;
    public static final int FUTURE_TIMEOUT_SEC = 30;
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private static SimpleServletServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionServlet());
        server.enableSsl(true);
        server.start();
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test the requirement of issuing socket and receiving echo response
     *
     * @throws Exception on test failure
     */
    @Test
    public void testEcho() throws Exception
    {
        assertThat("server scheme", server.getServerUri().getScheme(), is("wss"));
        WebSocketClient client = new WebSocketClient(server.getSslContextFactory(), null, bufferPool);
        try
        {
            client.start();

            CaptureSocket clientSocket = new CaptureSocket();
            URI requestUri = server.getServerUri();
            System.err.printf("Request URI: %s%n", requestUri.toASCIIString());
            Future<Session> fut = client.connect(clientSocket, requestUri);

            // wait for connect
            Session session = fut.get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS);

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            RemoteEndpoint remote = session.getRemote();
            remote.sendString(msg);
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<String> captured = clientSocket.messages;
            assertThat("Text Message", captured.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT), is(msg));

            // Shutdown the socket
            clientSocket.close();
        }
        finally
        {
            client.stop();
        }
    }

    /**
     * Test that server session reports as secure
     *
     * @throws Exception on test failure
     */
    @Test
    public void testServerSessionIsSecure() throws Exception
    {
        assertThat("server scheme", server.getServerUri().getScheme(), is("wss"));
        WebSocketClient client = new WebSocketClient(server.getSslContextFactory(), null, bufferPool);
        try
        {
            client.setConnectTimeout(CONNECT_TIMEOUT);
            client.start();

            CaptureSocket clientSocket = new CaptureSocket();
            URI requestUri = server.getServerUri();
            System.err.printf("Request URI: %s%n", requestUri.toASCIIString());
            Future<Session> fut = client.connect(clientSocket, requestUri);

            // wait for connect
            Session session = fut.get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS);

            // Generate text frame
            RemoteEndpoint remote = session.getRemote();
            remote.sendString("session.isSecure");
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<String> captured = clientSocket.messages;
            assertThat("Server.session.isSecure", captured.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT), is("session.isSecure=true"));

            // Shutdown the socket
            clientSocket.close();
        }
        finally
        {
            client.stop();
        }
    }

    /**
     * Test that server session.upgradeRequest.requestURI reports correctly
     *
     * @throws Exception on test failure
     */
    @Test
    public void testServerSessionRequestURI() throws Exception
    {
        assertThat("server scheme", server.getServerUri().getScheme(), is("wss"));
        WebSocketClient client = new WebSocketClient(server.getSslContextFactory(), null, bufferPool);
        try
        {
            client.setConnectTimeout(CONNECT_TIMEOUT);
            client.start();

            CaptureSocket clientSocket = new CaptureSocket();
            URI requestUri = server.getServerUri().resolve("/deep?a=b");
            System.err.printf("Request URI: %s%n", requestUri.toASCIIString());
            Future<Session> fut = client.connect(clientSocket, requestUri);

            // wait for connect
            Session session = fut.get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS);

            // Generate text frame
            RemoteEndpoint remote = session.getRemote();
            remote.sendString("session.upgradeRequest.requestURI");
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<String> captured = clientSocket.messages;
            String expected = String.format("session.upgradeRequest.requestURI=%s", requestUri.toASCIIString());
            assertThat("session.upgradeRequest.requestURI", captured.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT), is(expected));

            // Shutdown the socket
            clientSocket.close();
        }
        finally
        {
            client.stop();
        }
    }
}
