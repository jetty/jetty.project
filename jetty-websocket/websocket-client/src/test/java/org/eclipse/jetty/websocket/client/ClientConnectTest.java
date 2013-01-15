//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer.ServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/**
 * Various connect condition testing
 */
public class ClientConnectTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    private BlockheadServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
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

    @Test(expected = UpgradeException.class)
    public void testBadHandshake() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        ServerConnection connection = server.accept();
        connection.readRequest();
        // no upgrade, just fail with a 404 error
        connection.respond("HTTP/1.1 404 NOT FOUND\r\n\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path - throw underlying exception
            FutureCallback.rethrow(e);
        }
    }

    @Test(expected = UpgradeException.class)
    public void testBadUpgrade() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        ServerConnection connection = server.accept();
        connection.readRequest();
        // Upgrade badly
        connection.respond("HTTP/1.1 101 Upgrade\r\n" + "Sec-WebSocket-Accept: rubbish\r\n" + "\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path - throw underlying exception
            FutureCallback.rethrow(e);
        }
    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // Intentionally not accept incoming socket.
        // server.accept();

        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Should have Timed Out");
        }
        catch (TimeoutException e)
        {
            // Expected Path
            wsocket.assertNotOpened();
        }
    }

    @Test(expected = ConnectException.class)
    @Ignore("Need to get information about connection issue out of SelectManager somehow")
    public void testConnectionRefused() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        // Intentionally bad port
        URI wsUri = new URI("ws://127.0.0.1:1");
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(1000,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> ConnectException");
        }
        catch (ExecutionException e)
        {
            // Expected Path - throw underlying exception
            FutureCallback.rethrow(e);
        }
    }

    @Test(expected = TimeoutException.class)
    public void testConnectionTimeout() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        ServerConnection ssocket = server.accept();
        Assert.assertNotNull(ssocket);
        // Intentionally don't upgrade
        // ssocket.upgrade();

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> TimeoutException");
        }
        catch (ExecutionException e)
        {
            // Expected Path - throw underlying exception
            FutureCallback.rethrow(e);
        }
    }
}
