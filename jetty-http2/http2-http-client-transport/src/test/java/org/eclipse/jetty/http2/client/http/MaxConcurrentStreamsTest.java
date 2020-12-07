//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MaxConcurrentStreamsTest extends AbstractTest
{
    private void start(int maxConcurrentStreams, Handler handler) throws Exception
    {
        startServer(maxConcurrentStreams, handler);
        prepareClient();
        client.start();
    }

    private void startServer(int maxConcurrentStreams, Handler handler) throws Exception
    {
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        http2.setMaxConcurrentStreams(maxConcurrentStreams);
        prepareServer(http2);
        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testOneConcurrentStream() throws Exception
    {
        long sleep = 1000;
        start(1, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                // Sleep a bit to allow the second request to be queued.
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        CountDownLatch latch = new CountDownLatch(2);

        // First request is sent immediately.
        client.newRequest("localhost", connector.getLocalPort())
            .path("/first")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        // Second request is queued.
        client.newRequest("localhost", connector.getLocalPort())
            .path("/second")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        // When the first request returns, the second must be sent.
        assertTrue(latch.await(5 * sleep, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testManyIterationsWithConcurrentStreams() throws Exception
    {
        int concurrency = 1;
        start(concurrency, new EmptyServerHandler());

        int iterations = 50;
        IntStream.range(0, concurrency).parallel().forEach(i ->
            IntStream.range(0, iterations).forEach(j ->
            {
                try
                {
                    ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                        .path("/" + i + "_" + j)
                        .timeout(5, TimeUnit.SECONDS)
                        .send();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                }
                catch (Throwable x)
                {
                    throw new RuntimeException(x);
                }
            })
        );
    }

    @Test
    public void testSmallMaxConcurrentStreamsExceededOnClient() throws Exception
    {
        int maxConcurrentStreams = 1;
        startServer(maxConcurrentStreams, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(1000);
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        AtomicInteger connections = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())
        {
            @Override
            protected void connect(InetSocketAddress address, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
            {
                super.connect(address, factory, new Wrapper(listener)
                {
                    @Override
                    public void onSettings(Session session, SettingsFrame frame)
                    {
                        super.onSettings(session, frame);
                        // Send another request to simulate a request being
                        // sent concurrently with connection establishment.
                        // Sending this request will trigger the creation of
                        // another connection since maxConcurrentStream=1.
                        if (connections.incrementAndGet() == 1)
                        {
                            client.newRequest(host, port)
                                .path("/2")
                                .send(result ->
                                {
                                    if (result.isSucceeded())
                                    {
                                        Response response2 = result.getResponse();
                                        if (response2.getStatus() == HttpStatus.OK_200)
                                            latch.countDown();
                                        else
                                            failures.add(new HttpResponseException("", response2));
                                    }
                                    else
                                    {
                                        failures.add(result.getFailure());
                                    }
                                });
                        }
                    }
                }, promise, context);
            }
        });
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.start();

        // This request will be queued and establish the connection,
        // which will trigger the send of the second request.
        var request = client.newRequest(host, port)
                .path("/1")
                .timeout(5, TimeUnit.SECONDS);
        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS), failures.toString());
        assertEquals(2, connections.get());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(2, connectionPool.getConnectionCount());
    }

    @Test
    public void testTwoConcurrentStreamsThirdWaits() throws Exception
    {
        int maxStreams = 2;
        long sleep = 1000;
        start(maxStreams, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        // Send requests up to the max allowed.
        for (int i = 0; i < maxStreams; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/" + i)
                .send(null);
        }

        // Send the request in excess.
        CountDownLatch latch = new CountDownLatch(1);
        String path = "/excess";
        var request = client.newRequest("localhost", connector.getLocalPort()).path(path);
        request.send(result ->
        {
            if (result.getResponse().getStatus() == HttpStatus.OK_200)
                latch.countDown();
        });

        // The last exchange should remain in the queue.
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        assertEquals(1, destination.getHttpExchanges().size());
        assertEquals(path, destination.getHttpExchanges().peek().getRequest().getPath());

        assertTrue(latch.await(5 * sleep, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testAbortedWhileQueued() throws Exception
    {
        long sleep = 1000;
        start(1, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        // Send a request that is aborted while queued.
        client.newRequest("localhost", connector.getLocalPort())
            .path("/aborted")
            .onRequestQueued(request -> request.abort(new Exception()))
            .send(null);

        // Must be able to send another request.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort()).path("/check").send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testMultipleRequestsQueuedOnConnect() throws Exception
    {
        int maxConcurrent = 10;
        long sleep = 500;
        start(maxConcurrent, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        // The first request will open the connection, the others will be queued.
        CountDownLatch latch = new CountDownLatch(maxConcurrent);
        for (int i = 0; i < maxConcurrent; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/" + i)
                .send(result -> latch.countDown());
        }

        // The requests should be processed in parallel, not sequentially.
        assertTrue(latch.await(maxConcurrent * sleep / 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testManyConcurrentRequestsWithSmallConcurrentStreams() throws Exception
    {
        byte[] data = new byte[64 * 1024];
        start(1, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(data);
            }
        });

        int parallelism = 4;
        int runs = 1;
        int iterations = 32;

        client.setMaxConnectionsPerDestination(32768);
        client.setMaxRequestsQueuedPerDestination(1024 * 1024);
        client.getTransport().setConnectionPoolFactory(destination ->
        {
            try
            {
                MultiplexConnectionPool pool = new MultiplexConnectionPool(destination, client.getMaxConnectionsPerDestination(), false, destination, 1);
                pool.preCreateConnections(parallelism * 2).get();
                return pool;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
        // Prime the destination to pre-create connections.
        client.GET("http://localhost:" + connector.getLocalPort());

        int total = parallelism * runs * iterations;
        CountDownLatch latch = new CountDownLatch(total);
        Queue<Result> failures = new ConcurrentLinkedQueue<>();
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        pool.submit(() -> IntStream.range(0, parallelism).parallel().forEach(i ->
            IntStream.range(0, runs).forEach(j ->
            {
                for (int k = 0; k < iterations; ++k)
                {
                    client.newRequest("localhost", connector.getLocalPort())
                        .path("/" + i + "_" + j + "_" + k)
                        .send(result ->
                        {
                            if (result.isFailed())
                                failures.offer(result);
                            latch.countDown();
                        });
                }
            })));

        assertTrue(latch.await(total * 10, TimeUnit.MILLISECONDS));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    public void testTwoStreamsFirstTimesOut() throws Exception
    {
        long timeout = 1000;
        start(1, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                if (target.endsWith("/1"))
                    sleep(2 * timeout);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/1")
            .timeout(timeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .path("/2")
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertTrue(latch.await(2 * timeout, TimeUnit.MILLISECONDS));
    }

    private void primeConnection() throws Exception
    {
        // Prime the connection so that the maxConcurrentStream setting arrives to the client.
        client.newRequest("localhost", connector.getLocalPort()).path("/prime").send();
        // Wait for the server to clean up and remove the stream that primes the connection.
        sleep(1000);
    }

    private void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private static class Wrapper implements Session.Listener
    {
        private final Session.Listener listener;

        private Wrapper(Session.Listener listener)
        {
            this.listener = listener;
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            return listener.onPreface(session);
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            return listener.onNewStream(stream, frame);
        }

        @Override
        public void onSettings(Session session, SettingsFrame frame)
        {
            listener.onSettings(session, frame);
        }

        @Override
        public void onPing(Session session, PingFrame frame)
        {
            listener.onPing(session, frame);
        }

        @Override
        public void onReset(Session session, ResetFrame frame)
        {
            listener.onReset(session, frame);
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame)
        {
            listener.onClose(session, frame);
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            return listener.onIdleTimeout(session);
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            listener.onFailure(session, failure);
        }
    }
}
