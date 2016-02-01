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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ConnectTimeoutTest extends AbstractTest
{
    @Test
    public void testConnectTimeout() throws Exception
    {
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new ServerSessionListener.Adapter());
        client.setConnectTimeout(connectTimeout);

        InetSocketAddress address = new InetSocketAddress(host, port);
        final CountDownLatch latch = new CountDownLatch(1);
        client.connect(address, new Session.Listener.Adapter(), new Promise.Adapter<Session>()
        {
            @Override
            public void failed(Throwable x)
            {
                Assert.assertTrue(x instanceof SocketTimeoutException);
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
    }

    private void assumeConnectTimeout(String host, int port, int connectTimeout) throws IOException
    {
        boolean socketTimeout = false;
        
        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
        }
        catch (SocketTimeoutException x)
        {
            // We expect a timeout during connect, allow test to continue.
            socketTimeout = true;
        }
        catch (Throwable x)
        {
            // Dump stacktrace when there is an unexpected test failure
            // Useful when debugging
            x.printStackTrace(System.err);
        }
        
        // Abort the test if we can connect.
        Assume.assumeTrue("Should have seen connect timeout",socketTimeout);
    }
}
