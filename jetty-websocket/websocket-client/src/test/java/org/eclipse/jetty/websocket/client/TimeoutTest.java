//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.BlockheadServer.ServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Various tests for Timeout handling
 */
public class TimeoutTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    private BlockheadServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getPolicy().setIdleTimeout(250); // idle timeout (for all tests here)
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

    /**
     * In a situation where the upgrade/connection is successful, and there is no activity for a while, the idle timeout triggers on the client side and
     * automatically initiates a close handshake.
     */
    @Test
    public void testIdleDetectedByClient() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        client.setMaxIdleTimeout(1000);
        Future<Session> future = client.connect(wsocket,wsUri);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        try
        {
            ssocket.startEcho();
            // Validate that connect occurred
            future.get(500,TimeUnit.MILLISECONDS);
            wsocket.waitForConnected(500,TimeUnit.MILLISECONDS);

            // Wait for inactivity idle timeout.
            long start = System.currentTimeMillis();
            wsocket.waitForClose(2,TimeUnit.SECONDS);
            long end = System.currentTimeMillis();
            long dur = (end - start);
            // Make sure idle timeout takes less than 5 total seconds
            Assert.assertThat("Idle Timeout",dur,lessThanOrEqualTo(3000L));

            // Client should see a close event, with abnormal status NO_CLOSE
            wsocket.assertCloseCode(StatusCode.ABNORMAL);
        }
        finally
        {
            ssocket.stopEcho();
        }
    }
}
