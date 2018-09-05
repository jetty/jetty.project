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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

// TODO: these tests seems to fail spuriously, figure out why.
@Ignore
public class HttpClientConnectTimeoutTest extends AbstractTest
{
    public HttpClientConnectTimeoutTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testConnectTimeout() throws Exception
    {
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        Assert.assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(request.getAbortCause());
    }

    @Test
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout() throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 2000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        Request request = client.newRequest(host, port);
        request.timeout(connectTimeout / 2, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                    completes.incrementAndGet();
                    latch.countDown();
                });

        Assert.assertFalse(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(1, completes.get());
        Assert.assertNotNull(request.getAbortCause());
    }

    @Test
    public void retryAfterConnectTimeout() throws Exception
    {
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.send(result1 ->
        {
            if (result1.isFailed())
            {
                // Retry
                client.newRequest(host, port).send(result2 ->
                {
                    if (result2.isFailed())
                        latch.countDown();
                });
            }
        });

        Assert.assertTrue(latch.await(3 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(request.getAbortCause());
    }

    private void assumeConnectTimeout(String host, int port, int connectTimeout) throws IOException
    {
        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            // Abort the test if we can connect.
            Assume.assumeTrue(false);
        }
        catch (SocketTimeoutException x)
        {
            // Expected timeout during connect, continue the test.
            Assume.assumeTrue(true);
        }
        catch (Throwable x)
        {
            // Abort if any other exception happens.
            Assume.assumeTrue(false);
        }
    }
}
