//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for conditions due to bad networking.
 */
public class BadNetworkTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private static BlockheadServer server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient(bufferPool);
        client.getPolicy().setIdleTimeout(250);
        client.start();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAbruptClientClose() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // Validate that we are connected
        future.get(30,TimeUnit.SECONDS);
        wsocket.waitForConnected();

        // Have client disconnect abruptly
        Session session = wsocket.getSession();
        session.disconnect();

        // Client Socket should see close
        wsocket.waitForClose(10,TimeUnit.SECONDS);

        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        wsocket.assertCloseCode(StatusCode.NO_CLOSE);
    }

    @Test
    public void testAbruptServerClose() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Validate that we are connected
            future.get(30, TimeUnit.SECONDS);
            wsocket.waitForConnected();

            // Have server disconnect abruptly
            serverConn.abort();

            // Wait for close (as response to idle timeout)
            wsocket.waitForClose(10, TimeUnit.SECONDS);

            // Client Socket should see a close event, with status NO_CLOSE
            // This event is automatically supplied by the underlying WebSocketClientConnection
            // in the situation of a bad network connection.
            wsocket.assertCloseCode(StatusCode.NO_CLOSE);
        }
    }
}
