//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for conditions due to bad networking.
 */
public class BadNetworkTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private BlockheadServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient(bufferPool);
        client.getPolicy().setIdleTimeout(250);
        client.start();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAbruptClientClose() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection ssocket = server.accept();
        ssocket.upgrade();

        // Validate that we are connected
        future.get(30,TimeUnit.SECONDS);
        wsocket.waitForConnected(30,TimeUnit.SECONDS);

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

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection ssocket = server.accept();
        ssocket.upgrade();

        // Validate that we are connected
        future.get(30,TimeUnit.SECONDS);
        wsocket.waitForConnected(30,TimeUnit.SECONDS);

        // Have server disconnect abruptly
        ssocket.disconnect();

        // Wait for close (as response to idle timeout)
        wsocket.waitForClose(10,TimeUnit.SECONDS);

        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        wsocket.assertCloseCode(StatusCode.NO_CLOSE);
    }
}
