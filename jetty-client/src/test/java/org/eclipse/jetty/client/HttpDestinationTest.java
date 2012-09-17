//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpDestinationTest extends AbstractHttpClientServerTest
{
    public HttpDestinationTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Before
    public void init() throws Exception
    {
        start(new EmptyServerHandler());
    }

    @Test
    public void test_FirstAcquire_WithEmptyQueue() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection = destination.acquire();
        if (connection == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            connection = destination.getIdleConnections().poll(5, TimeUnit.SECONDS);
        }
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_SecondAcquire_AfterFirstAcquire_WithEmptyQueue_ReturnsSameConnection() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection1 = destination.acquire();
        if (connection1 == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            long start = System.nanoTime();
            while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            {
                connection1 = destination.getIdleConnections().peek();
                TimeUnit.MILLISECONDS.sleep(50);
            }
            Assert.assertNotNull(connection1);

            Connection connection2 = destination.acquire();
            Assert.assertSame(connection1, connection2);
        }
    }

    @Test
    public void test_SecondAcquire_ConcurrentWithFirstAcquire_WithEmptyQueue_CreatesTwoConnections() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort())
        {
            @Override
            protected void process(Connection connection, boolean dispatch)
            {
                try
                {
                    latch.await(5, TimeUnit.SECONDS);
                    super.process(connection, dispatch);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }
        };
        Connection connection1 = destination.acquire();

        // There are no available existing connections, so acquire()
        // returns null because we delayed process() above
        Assert.assertNull(connection1);

        Connection connection2 = destination.acquire();
        Assert.assertNull(connection2);

        latch.countDown();

        // There must be 2 idle connections
        Connection connection = destination.getIdleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
        connection = destination.getIdleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_Acquire_Process_Release_Acquire_ReturnsSameConnection() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection1 = destination.acquire();
        if (connection1 == null)
            connection1 = destination.getIdleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection1);

        destination.process(connection1, false);
        destination.release(connection1);

        Connection connection2 = destination.acquire();
        Assert.assertSame(connection1, connection2);
    }

    @Slow
    @Test
    public void test_IdleConnection_IdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection1 = destination.acquire();
        if (connection1 == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            long start = System.nanoTime();
            while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            {
                connection1 = destination.getIdleConnections().peek();
                TimeUnit.MILLISECONDS.sleep(50);
            }
            Assert.assertNotNull(connection1);

            TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);

            connection1 = destination.getIdleConnections().poll();
            Assert.assertNull(connection1);
        }
    }
}
