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

package org.eclipse.jetty.http2.client.http;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Pool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Sibling of ConnectionPoolTest, but using H2 to multiplex connections.
public class MultiplexedConnectionPoolTest
{
    private static final int MAX_MULTIPLEX = 2;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        HTTP2ServerConnectionFactory http2ServerConnectionFactory = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        http2ServerConnectionFactory.setMaxConcurrentStreams(MAX_MULTIPLEX);
        connector = new ServerConnector(server, 1, 1, http2ServerConnectionFactory);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void disposeServer() throws Exception
    {
        connector = null;
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }

    @AfterEach
    public void disposeClient() throws Exception
    {
        if (client != null)
        {
            client.stop();
            client = null;
        }
    }

    @Test
    public void testMaxDurationConnectionsWithMultiplexedPool() throws Exception
    {
        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        AtomicReference<Pool<Connection>> poolRef = new AtomicReference<>();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            int maxConnections = destination.getHttpClient().getMaxConnectionsPerDestination();
            Pool<Connection> pool = new Pool<>(Pool.StrategyType.FIRST, maxConnections, false);
            poolRef.set(pool);
            MultiplexConnectionPool connectionPool = new MultiplexConnectionPool(destination, pool, destination, MAX_MULTIPLEX)
            {
                @Override
                protected void onCreated(Connection connection)
                {
                    poolCreateCounter.incrementAndGet();
                }

                @Override
                protected void removed(Connection connection)
                {
                    poolRemoveCounter.incrementAndGet();
                }
            };
            connectionPool.setMaxDuration(maxDuration);
            return connectionPool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client());
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport, null);
        client.start();

        // Use the connection pool 5 times with a delay that is longer than the max duration in between each time.
        for (int i = 0; i < 5; i++)
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), Matchers.is(200));

            // Check that the pool never grows above 1.
            assertThat(poolRef.get().size(), is(1));

            Thread.sleep(maxDuration * 2);
        }

        // Check that the pool created 5 and removed 4 connections;
        // it must be exactly 4 removed b/c while the pool is not
        // constrained, it can multiplex requests on a single connection
        // so that should prevent opening more connections than needed.
        assertThat(poolCreateCounter.get(), is(5));
        assertThat(poolRemoveCounter.get(), is(4));
    }

    @Test
    public void testMaxDurationConnectionsWithMultiplexedPoolClosesExpiredConnectionWhileStillInUse() throws Exception
    {
        final int maxDuration = 1000;
        final int maxIdle = 2000;

        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        AtomicReference<Pool<Connection>> poolRef = new AtomicReference<>();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            int maxConnections = destination.getHttpClient().getMaxConnectionsPerDestination();
            Pool<Connection> pool = new Pool<>(Pool.StrategyType.FIRST, maxConnections, false);
            poolRef.set(pool);
            MultiplexConnectionPool connectionPool = new MultiplexConnectionPool(destination, pool, destination, MAX_MULTIPLEX)
            {
                @Override
                protected void onCreated(Connection connection)
                {
                    poolCreateCounter.incrementAndGet();
                }

                @Override
                protected void removed(Connection connection)
                {
                    poolRemoveCounter.incrementAndGet();
                }
            };
            connectionPool.setMaxDuration(maxDuration);
            return connectionPool;
        });

        Semaphore handlerSignalingSemaphore = new Semaphore(0);
        Semaphore handlerWaitingSemaphore = new Semaphore(0);
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                if (!target.equals("/block"))
                    return;

                handlerSignalingSemaphore.release();

                try
                {
                    handlerWaitingSemaphore.acquire();
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        });

        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client());
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport, null);
        client.setIdleTimeout(maxIdle);
        client.start();

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(2);
        // create 2 requests that are going to consume all the multiplexing slots
        client.newRequest("localhost", connector.getLocalPort())
            .path("/block")
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded())
                {
                    latch1.countDown();
                    latch2.countDown();
                }
            });

        // wait for the 1st request to be serviced to make sure only 1 connection gets created
        handlerSignalingSemaphore.acquire();

        client.newRequest("localhost", connector.getLocalPort())
            .path("/block")
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded())
                {
                    latch1.countDown();
                    latch2.countDown();
                }
            });

        // wait for both requests to start being serviced
        handlerSignalingSemaphore.acquire();

        assertThat(poolCreateCounter.get(), is(1));

        // finalize 1 request, freeing up 1 multiplexing slot
        handlerWaitingSemaphore.release();
        // wait until 1st request finished
        assertTrue(latch1.await(5, TimeUnit.SECONDS));

        assertThat(poolRef.get().getInUseCount(), is(1));
        assertThat(poolRef.get().getIdleCount(), is(0));
        assertThat(poolRef.get().getClosedCount(), is(0));
        assertThat(poolRef.get().size(), is(1));

        // wait for the connection to expire
        Thread.sleep(maxDuration + 500);

        // send a 3rd request that will close the expired multiplexed connection
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .path("/do-not-block")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));

        assertThat(poolRef.get().getInUseCount(), is(0));
        assertThat(poolRef.get().getIdleCount(), is(1));
        assertThat(poolRef.get().getClosedCount(), is(1));
        assertThat(poolRef.get().size(), is(2));

        // unblock 2nd request
        handlerWaitingSemaphore.release();
        //wait until 2nd request finished
        assertTrue(latch2.await(5, TimeUnit.SECONDS));

        assertThat(poolRef.get().getInUseCount(), is(0));
        assertThat(poolRef.get().getIdleCount(), is(1));
        assertThat(poolRef.get().getClosedCount(), is(0));
        assertThat(poolRef.get().size(), is(1));
        assertThat(poolCreateCounter.get(), is(2));

        // wait for idle connections to be closed
        Thread.sleep(maxIdle + 500);

        assertThat(poolRef.get().getIdleCount(), is(0));
        assertThat(poolRef.get().size(), is(0));
        assertThat(poolRemoveCounter.get(), is(3));
    }

    private static class ConnectionPoolFactory
    {
        private final String name;
        private final ConnectionPool.Factory factory;

        private ConnectionPoolFactory(String name, ConnectionPool.Factory factory)
        {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
