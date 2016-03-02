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

package org.eclipse.jetty.client.http;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpDestinationOverHTTPTest extends AbstractHttpClientServerTest
{
    public HttpDestinationOverHTTPTest(SslContextFactory sslContextFactory)
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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()));
        destination.start();
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Connection connection = connectionPool.acquire();
        if (connection == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            connection = timedPoll(connectionPool.getIdleConnections(), 5, TimeUnit.SECONDS);
        }
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_SecondAcquire_AfterFirstAcquire_WithEmptyQueue_ReturnsSameConnection() throws Exception
    {
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()));
        destination.start();
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Connection connection1 = connectionPool.acquire();
        if (connection1 == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            long start = System.nanoTime();
            while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            {
                TimeUnit.MILLISECONDS.sleep(50);
                connection1 = connectionPool.getIdleConnections().peek();
            }
            Assert.assertNotNull(connection1);

            Connection connection2 = connectionPool.acquire();
            Assert.assertSame(connection1, connection2);
        }
    }

    @Test
    public void test_SecondAcquire_ConcurrentWithFirstAcquire_WithEmptyQueue_CreatesTwoConnections() throws Exception
    {
        final CountDownLatch idleLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()))
        {
            @Override
            protected ConnectionPool newConnectionPool(HttpClient client)
            {
                return new DuplexConnectionPool(this, client.getMaxConnectionsPerDestination(), this)
                {
                    @Override
                    protected void onCreated(Connection connection)
                    {
                        try
                        {
                            idleLatch.countDown();
                            latch.await(5, TimeUnit.SECONDS);
                            super.onCreated(connection);
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }
                };
            }
        };
        destination.start();
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Connection connection1 = connectionPool.acquire();

        // Make sure we entered idleCreated().
        Assert.assertTrue(idleLatch.await(5, TimeUnit.SECONDS));

        // There are no available existing connections, so acquire()
        // returns null because we delayed idleCreated() above
        Assert.assertNull(connection1);

        // Second attempt also returns null because we delayed idleCreated() above.
        Connection connection2 = connectionPool.acquire();
        Assert.assertNull(connection2);

        latch.countDown();

        // There must be 2 idle connections.
        Queue<Connection> idleConnections = connectionPool.getIdleConnections();
        Connection connection = timedPoll(idleConnections, 5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
        connection = timedPoll(idleConnections, 5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_Acquire_Process_Release_Acquire_ReturnsSameConnection() throws Exception
    {
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()));
        destination.start();
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        HttpConnectionOverHTTP connection1 = (HttpConnectionOverHTTP)connectionPool.acquire();

        long start = System.nanoTime();
        while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            TimeUnit.MILLISECONDS.sleep(50);
            connection1 = (HttpConnectionOverHTTP)connectionPool.getIdleConnections().peek();
        }
        Assert.assertNotNull(connection1);

        // Acquire the connection to make it active
        Assert.assertSame(connection1, connectionPool.acquire());

        destination.process(connection1);
        destination.release(connection1);

        Connection connection2 = connectionPool.acquire();
        Assert.assertSame(connection1, connection2);
    }

    @Test
    public void test_IdleConnection_IdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()));
        destination.start();
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Connection connection1 = connectionPool.acquire();
        if (connection1 == null)
        {
            // There are no queued requests, so the newly created connection will be idle
            long start = System.nanoTime();
            while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            {
                TimeUnit.MILLISECONDS.sleep(50);
                connection1 = connectionPool.getIdleConnections().peek();
            }
            Assert.assertNotNull(connection1);

            TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);

            connection1 = connectionPool.getIdleConnections().poll();
            Assert.assertNull(connection1);
        }
    }

    @Test
    public void test_Request_Failed_If_MaxRequestsQueuedPerDestination_Exceeded() throws Exception
    {
        int maxQueued = 1;
        client.setMaxRequestsQueuedPerDestination(maxQueued);
        client.setMaxConnectionsPerDestination(1);

        // Make one request to open the connection and be sure everything is setup properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();
        Assert.assertEquals(200, response.getStatus());

        // Send another request that is sent immediately
        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/one")
                .onRequestQueued(request ->
                {
                    // This request exceeds the maximum queued, should fail
                    client.newRequest("localhost", connector.getLocalPort())
                            .scheme(scheme)
                            .path("/two")
                            .send(result ->
                            {
                                Assert.assertTrue(result.isFailed());
                                Assert.assertThat(result.getRequestFailure(), Matchers.instanceOf(RejectedExecutionException.class));
                                failureLatch.countDown();
                            });
                })
                .send(result ->
                {
                    if (result.isSucceeded())
                        successLatch.countDown();
                });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDestinationIsRemoved() throws Exception
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        Destination destinationBefore = client.getDestination(scheme, host, port);

        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .send();

        Assert.assertEquals(200, response.getStatus());

        Destination destinationAfter = client.getDestination(scheme, host, port);
        Assert.assertSame(destinationBefore, destinationAfter);

        client.setRemoveIdleDestinations(true);

        response = client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .send();

        Assert.assertEquals(200, response.getStatus());

        destinationAfter = client.getDestination(scheme, host, port);
        Assert.assertNotSame(destinationBefore, destinationAfter);
    }

    private Connection timedPoll(Queue<Connection> connections, long time, TimeUnit unit) throws InterruptedException
    {
        long start = System.nanoTime();
        while (unit.toNanos(time) > System.nanoTime() - start)
        {
            Connection connection = connections.poll();
            if (connection != null)
                return connection;
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return connections.poll();
    }
}
