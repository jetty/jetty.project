//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HttpClientConnectTimeoutTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoUnixDomain")
    public void testConnectTimeout(Transport transport) throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 1000;
        assumeTrue(connectTimeout(host, port, connectTimeout));

        start(transport, new EmptyServerHandler());
        client.setConnectTimeout(connectTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @MethodSource("transportsNoUnixDomain")
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout(Transport transport) throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 2000;
        assumeTrue(connectTimeout(host, port, connectTimeout));

        start(transport, new EmptyServerHandler());
        client.setConnectTimeout(connectTimeout);

        AtomicInteger completes = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        Request request = client.newRequest(host, port);
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
    @MethodSource("transportsNoUnixDomain")
    public void retryAfterConnectTimeout(Transport transport) throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 1000;
        assumeTrue(connectTimeout(host, port, connectTimeout));

        start(transport, new EmptyServerHandler());
        client.setConnectTimeout(connectTimeout);

        CountDownLatch latch = new CountDownLatch(1);
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

        assertTrue(latch.await(3 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }

    private boolean connectTimeout(String host, int port, int connectTimeout) throws IOException
    {
        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            return false;
        }
        catch (Throwable x)
        {
            // Expected timeout during connect, or no route to host, continue the test.
            return true;
        }
    }
}
