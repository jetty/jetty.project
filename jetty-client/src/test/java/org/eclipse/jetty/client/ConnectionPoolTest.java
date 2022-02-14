//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionPoolTest
{
    private static final ConnectionPoolFactory DUPLEX = new ConnectionPoolFactory("duplex", destination -> new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination));
    private static final ConnectionPoolFactory MULTIPLEX = new ConnectionPoolFactory("multiplex", destination -> new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1));
    private static final ConnectionPoolFactory RANDOM = new ConnectionPoolFactory("random", destination -> new RandomConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1));
    private static final ConnectionPoolFactory DUPLEX_MAX_DURATION = new ConnectionPoolFactory("duplex-maxDuration", destination ->
    {
        DuplexConnectionPool pool = new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination);
        pool.setMaxDuration(10);
        return pool;
    });
    private static final ConnectionPoolFactory ROUND_ROBIN = new ConnectionPoolFactory("round-robin", destination -> new RoundRobinConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination));

    public static Stream<ConnectionPoolFactory> pools()
    {
        return Stream.of(DUPLEX, MULTIPLEX, RANDOM, DUPLEX_MAX_DURATION, ROUND_ROBIN);
    }

    public static Stream<ConnectionPoolFactory> poolsNoRoundRobin()
    {
        return Stream.of(DUPLEX, MULTIPLEX, RANDOM, DUPLEX_MAX_DURATION);
    }

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void start(ConnectionPool.Factory factory, Handler handler) throws Exception
    {
        startServer(handler);
        startClient(factory);
    }

    private void startClient(ConnectionPool.Factory factory) throws Exception
    {
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory);
        client = new HttpClient(transport, null);
        client.start();
    }

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
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

    @ParameterizedTest
    @MethodSource("pools")
    public void test(ConnectionPoolFactory factory) throws Exception
    {
        start(factory.factory, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                switch (HttpMethod.fromString(request.getMethod()))
                {
                    case GET:
                    {
                        int contentLength = request.getIntHeader("X-Download");
                        if (contentLength > 0)
                        {
                            response.setContentLength(contentLength);
                            response.getOutputStream().write(new byte[contentLength]);
                        }
                        break;
                    }
                    case POST:
                    {
                        int contentLength = request.getContentLength();
                        if (contentLength > 0)
                            response.setContentLength(contentLength);
                        IO.copy(request.getInputStream(), response.getOutputStream());
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }

                if (Boolean.parseBoolean(request.getHeader("X-Close")))
                    response.setHeader("Connection", "close");
            }
        });

        int parallelism = 16;
        int runs = 2;
        int iterations = 1024;
        CountDownLatch latch = new CountDownLatch(parallelism * runs);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        IntStream.range(0, parallelism).parallel().forEach(i ->
            IntStream.range(0, runs).forEach(j ->
                run(latch, iterations, failures)));
        assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private void run(CountDownLatch latch, int iterations, List<Throwable> failures)
    {
        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            test(failures);
        }
        long end = System.nanoTime();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
        System.err.printf("%d requests in %d ms, %.3f req/s%n", iterations, elapsed, elapsed > 0 ? iterations * 1000D / elapsed : -1D);
        latch.countDown();
    }

    private void test(List<Throwable> failures)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Choose a random method.
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;

        // Choose randomly whether to close the connection on the client or on the server.
        boolean clientClose = false;
        if (random.nextInt(100) < 1)
            clientClose = true;
        boolean serverClose = false;
        if (random.nextInt(100) < 1)
            serverClose = true;

        int maxContentLength = 64 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        test(method, clientClose, serverClose, contentLength, failures);
    }

    private void test(HttpMethod method, boolean clientClose, boolean serverClose, int contentLength, List<Throwable> failures)
    {
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .path("/")
            .method(method);

        if (clientClose)
            request.header(HttpHeader.CONNECTION, "close");
        else if (serverClose)
            request.header("X-Close", "true");

        switch (method)
        {
            case GET:
                request.header("X-Download", String.valueOf(contentLength));
                break;
            case POST:
                request.header(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
                request.content(new BytesContentProvider(new byte[contentLength]));
                break;
            default:
                throw new IllegalStateException();
        }

        FutureResponseListener listener = new FutureResponseListener(request, contentLength);
        request.send(listener);

        try
        {
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        catch (Throwable x)
        {
            failures.add(x);
        }
    }

    @ParameterizedTest
    @MethodSource("poolsNoRoundRobin")
    public void testQueuedRequestsDontOpenTooManyConnections(ConnectionPoolFactory factory) throws Exception
    {
        // Round robin connection pool does open a few more
        // connections than expected, exclude it from this test.

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport, null);
        long delay = 1000;
        client.setSocketAddressResolver(new SocketAddressResolver.Sync()
        {
            @Override
            public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
            {
                client.getExecutor().execute(() ->
                {
                    try
                    {
                        Thread.sleep(delay);
                        super.resolve(host, port, promise);
                    }
                    catch (InterruptedException x)
                    {
                        promise.failed(x);
                    }
                });
            }
        });
        client.start();

        CountDownLatch latch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/one")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });
        Thread.sleep(delay / 2);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/two")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        if (DUPLEX_MAX_DURATION == factory)
            assertThat(connectionPool.getConnectionCount(), lessThanOrEqualTo(2)); // The connections can expire upon release.
        else
            assertThat(connectionPool.getConnectionCount(), is(2));
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsWithSlowAddressResolver(ConnectionPoolFactory factory) throws Exception
    {
        // ConnectionPools may open a few more connections than expected.

        startServer(new EmptyServerHandler());

        int count = 500;
        QueuedThreadPool clientThreads = new QueuedThreadPool(2 * count);
        clientThreads.setName("client");
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport, null);
        client.setExecutor(clientThreads);
        client.setMaxConnectionsPerDestination(2 * count);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync()
        {
            @Override
            public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
            {
                client.getExecutor().execute(() ->
                {
                    try
                    {
                        Thread.sleep(100);
                        super.resolve(host, port, promise);
                    }
                    catch (InterruptedException x)
                    {
                        promise.failed(x);
                    }
                });
            }
        });
        client.start();

        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            clientThreads.execute(() -> client.newRequest("localhost", connector.getLocalPort())
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                }));
        }

        assertTrue(latch.await(count, TimeUnit.SECONDS));
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsAllBlockedOnServerWithLargeConnectionPool(ConnectionPoolFactory factory) throws Exception
    {
        int count = 50;
        testConcurrentRequestsAllBlockedOnServer(factory, count, 2 * count);
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsAllBlockedOnServerWithExactConnectionPool(ConnectionPoolFactory factory) throws Exception
    {
        int count = 50;
        testConcurrentRequestsAllBlockedOnServer(factory, count, count);
    }

    private void testConcurrentRequestsAllBlockedOnServer(ConnectionPoolFactory factory, int count, int maxConnections) throws Exception
    {
        CyclicBarrier barrier = new CyclicBarrier(count);

        QueuedThreadPool serverThreads = new QueuedThreadPool(2 * count);
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    barrier.await();
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
            }
        });
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool(2 * count);
        clientThreads.setName("client");
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport, null);
        client.setExecutor(clientThreads);
        client.setMaxConnectionsPerDestination(maxConnections);
        client.start();

        // Send N requests to the server, all waiting on the server.
        // This should open N connections, and the test verifies that
        // all N are sent (i.e. the client does not keep any queued).
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            int id = i;
            clientThreads.execute(() -> client.newRequest("localhost", connector.getLocalPort())
                .path("/" + id)
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                }));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "server requests " + barrier.getNumberWaiting() + "<" + count + " - client: " + client.dump());
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        // The max duration connection pool aggressively closes expired connections upon release, which interferes with this assertion.
        if (DUPLEX_MAX_DURATION != factory)
        {
            HttpDestination destination = (HttpDestination)destinations.get(0);
            AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
            assertThat(connectionPool.getConnectionCount(), Matchers.greaterThanOrEqualTo(count));
        }
    }

    @Test
    public void testMaxDurationConnectionsWithConstrainedPool() throws Exception
    {
        // ConnectionPool may NOT open more connections than expected because
        // it is constrained to a single connection in this test.

        final int maxConnections = 1;
        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            // Constrain the max pool size to 1.
            DuplexConnectionPool pool = new DuplexConnectionPool(destination, maxConnections, destination)
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
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
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

            Thread.sleep(maxDuration * 2);
        }

        // Check that the pool created 5 and removed 4 connections;
        // it must be exactly 4 removed b/c each cycle of the loop
        // can only open 1 connection as the pool is constrained to
        // maximum 1 connection.
        assertThat(poolCreateCounter.get(), Matchers.is(5));
        assertThat(poolRemoveCounter.get(), Matchers.is(4));
    }

    @Test
    public void testMaxDurationConnectionsWithUnconstrainedPool() throws Exception
    {
        // ConnectionPools may open a few more connections than expected.

        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            DuplexConnectionPool pool = new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination)
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
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
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

            Thread.sleep(maxDuration * 2);
        }

        // Check that the pool created 5 and removed at least 4 connections;
        // it can be more than 4 removed b/c each cycle of the loop may
        // open more than 1 connection as the pool is not constrained.
        assertThat(poolCreateCounter.get(), Matchers.is(5));
        assertThat(poolRemoveCounter.get(), Matchers.greaterThanOrEqualTo(4));
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConnectionMaxUsage(ConnectionPoolFactory factory) throws Exception
    {
        startServer(new EmptyServerHandler());

        int maxUsageCount = 2;
        startClient(destination ->
        {
            AbstractConnectionPool connectionPool = (AbstractConnectionPool)factory.factory.newConnectionPool(destination);
            connectionPool.setMaxUsageCount(maxUsageCount);
            connectionPool.setMaxDuration(0); // Disable max duration expiry as it may expire the connection between the 1st and 2nd request.
            return connectionPool;
        });
        client.setMaxConnectionsPerDestination(1);

        // Send first request, we are within the max usage count.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        HttpDestination destination = (HttpDestination)client.getDestinations().get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();

        assertEquals(0, connectionPool.getActiveConnectionCount());
        if (DUPLEX_MAX_DURATION == factory)
        {
            // The connections can expire upon release.
            assertThat(connectionPool.getIdleConnectionCount(), lessThanOrEqualTo(1));
            assertThat(connectionPool.getConnectionCount(), lessThanOrEqualTo(1));
        }
        else
        {
            assertThat(connectionPool.getIdleConnectionCount(), is(1));
            assertThat(connectionPool.getConnectionCount(), is(1));
        }

        // Send second request, max usage count will be reached,
        // the only connection must be closed.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());

        assertEquals(0, connectionPool.getActiveConnectionCount());
        assertEquals(0, connectionPool.getIdleConnectionCount());
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testIdleTimeoutNoRequests(ConnectionPoolFactory factory) throws Exception
    {
        startServer(new EmptyServerHandler());
        startClient(destination ->
        {
            try
            {
                ConnectionPool connectionPool = factory.factory.newConnectionPool(destination);
                connectionPool.preCreateConnections(1).get();
                return connectionPool;
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        // Trigger the creation of a destination, that will create the connection pool.
        HttpDestination destination = client.resolveDestination(new Origin("http", "localhost", connector.getLocalPort()));
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        if (DUPLEX_MAX_DURATION == factory)
            assertThat(connectionPool.getConnectionCount(), lessThanOrEqualTo(1)); // The connections can expire upon release.
        else
            assertThat(connectionPool.getConnectionCount(), is(1));

        // Wait for the pre-created connections to idle timeout.
        Thread.sleep(idleTimeout + idleTimeout / 2);

        assertEquals(0, connectionPool.getConnectionCount());
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
