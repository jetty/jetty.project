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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// TODO: these tests seems to fail spuriously, figure out why.
@Disabled
@Tag("Unstable")
public class HttpClientConnectTimeoutTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectTimeout(Transport transport) throws Exception
    {
        init(transport);
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        scenario.client.stop();
        scenario.client.setConnectTimeout(connectTimeout);
        scenario.client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = scenario.client.newRequest(host, port);
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout(Transport transport) throws Exception
    {
        init(transport);
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 2000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        scenario.client.stop();
        scenario.client.setConnectTimeout(connectTimeout);
        scenario.client.start();

        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        Request request = scenario.client.newRequest(host, port);
        request.timeout(connectTimeout / 2, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                completes.incrementAndGet();
                latch.countDown();
            });

        assertFalse(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertEquals(1, completes.get());
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void retryAfterConnectTimeout(Transport transport) throws Exception
    {
        init(transport);
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        scenario.client.stop();
        scenario.client.setConnectTimeout(connectTimeout);
        scenario.client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = scenario.client.newRequest(host, port);
        request.send(result1 ->
        {
            if (result1.isFailed())
            {
                // Retry
                scenario.client.newRequest(host, port).send(result2 ->
                {
                    if (result2.isFailed())
                        latch.countDown();
                });
            }
        });

        assertTrue(latch.await(3 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
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
            Assumptions.assumeTrue(false, "Should not have been able to connect to " + host + ":" + port);
        }
        catch (SocketTimeoutException x)
        {
            // Expected timeout during connect, continue the test.
            return;
        }
        catch (Throwable x)
        {
            // Abort if any other exception happens.
            fail(x);
        }
    }
}
