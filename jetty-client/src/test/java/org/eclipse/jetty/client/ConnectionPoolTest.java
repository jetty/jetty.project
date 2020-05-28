//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionPoolTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public static Stream<ConnectionPoolFactory> pools()
    {
        return Stream.of(
            new ConnectionPoolFactory("duplex", destination -> new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination)),
            new ConnectionPoolFactory("round-robin", destination -> new RoundRobinConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination)),
            new ConnectionPoolFactory("multiplex", destination -> new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1))
        );
    }

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
    @MethodSource("pools")
    public void testQueuedRequestsDontOpenTooManyConnections(ConnectionPoolFactory factory) throws Exception
    {
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
        assertEquals(2, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsDontOpenTooManyConnections(ConnectionPoolFactory factory) throws Exception
    {
        // Round robin connection pool does open a few more connections than expected.
        Assumptions.assumeFalse(factory.name.equals("round-robin"));

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
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertThat(connectionPool.getConnectionCount(), Matchers.lessThanOrEqualTo(count));
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
