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

package org.eclipse.jetty.http2.client.http;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Pool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Sibling of ConnectionPoolTest, but using H2 to multiplex connections.
public class MultiplexedConnectionPoolTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiplexedConnectionPoolTest.class);
    private static final int MAX_MULTIPLEX = 2;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        startServer(handler, MAX_MULTIPLEX, -1L);
    }

    private void startServer(Handler handler, int maxConcurrentStreams, long streamIdleTimeout) throws Exception
    {
        server = new Server();
        HTTP2ServerConnectionFactory http2ServerConnectionFactory = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        http2ServerConnectionFactory.setMaxConcurrentStreams(maxConcurrentStreams);
        if (streamIdleTimeout > 0)
            http2ServerConnectionFactory.setStreamIdleTimeout(streamIdleTimeout);
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
    public void testMaxDurationConnectionsWithMultiplexedPoolLifecycle() throws Exception
    {
        final int maxDuration = 200;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        AtomicReference<Pool<Connection>> poolRef = new AtomicReference<>();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("MaxDurationConnectionsWithMultiplexedPoolLifecycle", destination ->
        {
            int maxConnections = destination.getHttpClient().getMaxConnectionsPerDestination();
            MultiplexConnectionPool pool = new MultiplexConnectionPool(destination, Pool.StrategyType.FIRST, maxConnections, false, destination, 10)
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
            poolRef.set(pool.getBean(Pool.class));
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        CountDownLatch[] reqExecutingLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] reqExecutedLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] reqFinishingLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                int req = Integer.parseInt(target.substring(1));
                try
                {
                    LOG.debug("req {} is executing", req);
                    reqExecutingLatches[req].countDown();
                    Thread.sleep(250);
                    reqExecutedLatches[req].countDown();
                    LOG.debug("req {} executed", req);

                    assertTrue(reqFinishingLatches[req].await(5, TimeUnit.SECONDS));

                    response.getWriter().println("req " + req + " executed");
                    response.getWriter().flush();
                    LOG.debug("req {} successful", req);
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        });

        ClientConnector clientConnector = new ClientConnector();
        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.start();

        CountDownLatch[] reqClientSuccessLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};

        sendRequest(reqClientSuccessLatches, 0);
        // wait until handler is executing
        assertTrue(reqExecutingLatches[0].await(5, TimeUnit.SECONDS));
        LOG.debug("req 0 executing");

        sendRequest(reqClientSuccessLatches, 1);
        // wait until handler executed sleep
        assertTrue(reqExecutedLatches[1].await(5, TimeUnit.SECONDS));
        LOG.debug("req 1 executed");

        // Now the pool contains one connection that is expired but in use by 2 threads.

        sendRequest(reqClientSuccessLatches, 2);
        LOG.debug("req2 sent");
        assertTrue(reqExecutingLatches[2].await(5, TimeUnit.SECONDS));
        LOG.debug("req2 executing");

        // The 3rd request has tried the expired request and marked it as closed as it has expired, then used a 2nd one.

        // release and wait for req2 to be done before releasing req1
        reqFinishingLatches[2].countDown();
        assertTrue(reqClientSuccessLatches[2].await(5, TimeUnit.SECONDS));
        reqFinishingLatches[1].countDown();

        // release req0 once req1 is done; req 1 should not have closed the response as req 0 is still running
        assertTrue(reqClientSuccessLatches[1].await(5, TimeUnit.SECONDS));
        reqFinishingLatches[0].countDown();
        assertTrue(reqClientSuccessLatches[0].await(5, TimeUnit.SECONDS));

        // Check that the pool created 2 and removed 2 connections;
        // 2 were removed b/c waiting for req 2 means the 2nd connection
        // expired and has to be removed and closed upon being returned to the pool.
        assertThat(poolCreateCounter.get(), Matchers.is(2));
        assertThat(poolRemoveCounter.get(), Matchers.is(2));
        assertThat(poolRef.get().size(), Matchers.is(0));
    }

    private void sendRequest(CountDownLatch[] reqClientDoneLatches, int i)
    {
        client.newRequest("localhost", connector.getLocalPort())
            .path("/" + i)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertThat("req " + i + " failed", result.getResponse().getStatus(), Matchers.is(200));
                reqClientDoneLatches[i].countDown();
            });
    }

    @Test
    public void testStreamIdleTimeout() throws Exception
    {
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        AtomicReference<Pool<Connection>> poolRef = new AtomicReference<>();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("StreamIdleTimeout", destination ->
        {
            int maxConnections = destination.getHttpClient().getMaxConnectionsPerDestination();
            MultiplexConnectionPool pool = new MultiplexConnectionPool(destination, Pool.StrategyType.FIRST, maxConnections, false, destination, 10)
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
            poolRef.set(pool.getBean(Pool.class));
            return pool;
        });

        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                int req = Integer.parseInt(target.substring(1));
                try
                {
                    response.getWriter().println("req " + req + " executed");
                    response.getWriter().flush();
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        }, 64, 1L);

        ClientConnector clientConnector = new ClientConnector();
        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.start();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger();
        for (int i = 0; i < 100; i++)
        {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            client.newRequest("localhost", connector.getLocalPort())
                .path("/" + i)
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    counter.incrementAndGet();
                    cf.complete(null);
                });
            futures.add(cf);
        }

        // Wait for all requests to complete.
        for (CompletableFuture<Void> cf : futures)
        {
            cf.get(5, TimeUnit.SECONDS);
        }
        assertThat(counter.get(), is(100));

        // All remaining pooled connections should be in IDLE state.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            for (Pool<Connection>.Entry value : poolRef.get().values())
            {
                if (!value.isIdle())
                    return false;
            }
            return true;
        });
    }

    @Test
    public void testMaxDurationConnectionsWithMultiplexedPool() throws Exception
    {
        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        AtomicReference<Pool<Connection>> poolRef = new AtomicReference<>();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("maxDurationConnectionsWithMultiplexedPool", destination ->
        {
            int maxConnections = destination.getHttpClient().getMaxConnectionsPerDestination();
            MultiplexConnectionPool connectionPool = new MultiplexConnectionPool(destination, Pool.StrategyType.FIRST, maxConnections, false, destination, MAX_MULTIPLEX)
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
            poolRef.set(connectionPool.getBean(Pool.class));
            connectionPool.setMaxDuration(maxDuration);
            return connectionPool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client());
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
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
            MultiplexConnectionPool connectionPool = new MultiplexConnectionPool(destination, Pool.StrategyType.FIRST, maxConnections, false, destination, MAX_MULTIPLEX)
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
            poolRef.set(connectionPool.getBean(Pool.class));
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
        client = new HttpClient(transport);
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
        assertThat(poolRemoveCounter.get(), is(2));
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
