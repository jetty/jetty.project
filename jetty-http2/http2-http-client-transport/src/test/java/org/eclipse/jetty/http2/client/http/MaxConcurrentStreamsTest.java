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

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
            protected void connect(SocketAddress address, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
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

    @Test
    public void testTCPCongestedStreamTimesOut() throws Exception
    {
        CountDownLatch request1Latch = new CountDownLatch(1);
        RawHTTP2ServerConnectionFactory http2 = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                switch (request.getURI().getPath())
                {
                    case "/1":
                    {
                        // Do not return to cause TCP congestion.
                        assertTrue(awaitLatch(request1Latch, 15, TimeUnit.SECONDS));
                        MetaData.Response response1 = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response1, null, true), Callback.NOOP);
                        break;
                    }
                    case "/3":
                    {
                        MetaData.Response response3 = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response3, null, true), Callback.NOOP);
                        break;
                    }
                    default:
                    {
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.INTERNAL_SERVER_ERROR_500, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        break;
                    }
                }
                // Return a Stream listener that consumes the content.
                return new Stream.Listener.Adapter();
            }
        });
        http2.setMaxConcurrentStreams(2);
        // Set the HTTP/2 flow control windows very large so we can
        // cause TCP congestion, not HTTP/2 flow control congestion.
        http2.setInitialSessionRecvWindow(512 * 1024 * 1024);
        http2.setInitialStreamRecvWindow(512 * 1024 * 1024);
        prepareServer(http2);
        server.start();

        prepareClient();
        AtomicReference<AbstractEndPoint> clientEndPointRef = new AtomicReference<>();
        CountDownLatch clientEndPointLatch = new CountDownLatch(1);
        client = new HttpClient(new HttpClientTransportOverHTTP2(http2Client)
        {
            @Override
            public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
            {
                clientEndPointRef.set((AbstractEndPoint)endPoint);
                clientEndPointLatch.countDown();
                return super.newConnection(endPoint, context);
            }
        });
        client.setMaxConnectionsPerDestination(1);
        client.start();

        // First request must cause TCP congestion.
        CountDownLatch response1Latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort()).path("/1")
            .body(new BytesRequestContent(new byte[64 * 1024 * 1024]))
            .send(result ->
            {
                assertTrue(result.isSucceeded(), String.valueOf(result.getFailure()));
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                response1Latch.countDown();
            });

        // Wait until TCP congested.
        assertTrue(clientEndPointLatch.await(5, TimeUnit.SECONDS));
        AbstractEndPoint clientEndPoint = clientEndPointRef.get();
        long start = NanoTime.now();
        while (!clientEndPoint.getWriteFlusher().isPending())
        {
            assertThat(NanoTime.secondsElapsedFrom(start), Matchers.lessThan(15L));
            Thread.sleep(100);
        }
        // Wait for the selector to update the SelectionKey to OP_WRITE.
        Thread.sleep(1000);

        // Second request cannot be sent due to TCP congestion and times out.
        assertThrows(TimeoutException.class, () -> client.newRequest("localhost", connector.getLocalPort())
            .path("/2")
            .timeout(1000, TimeUnit.MILLISECONDS)
            .send());

        // Third request should succeed.
        CountDownLatch response3Latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/3")
            .send(result ->
            {
                assertTrue(result.isSucceeded(), String.valueOf(result.getFailure()));
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                response3Latch.countDown();
            });

        // Wait for the third request to generate the HTTP/2 stream.
        Thread.sleep(1000);

        // Resolve the TCP congestion.
        request1Latch.countDown();

        assertTrue(response1Latch.await(15, TimeUnit.SECONDS));
        assertTrue(response3Latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDifferentMaxConcurrentStreamsForDifferentConnections() throws Exception
    {
        long processing = 125;
        RawHTTP2ServerConnectionFactory http2 = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), new ServerSessionListener.Adapter()
        {
            private Session session1;
            private Session session2;

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                switch (request.getURI().getPath())
                {
                    case "/prime":
                    {
                        session1 = stream.getSession();
                        // Send another request from here to force the opening of the 2nd connection.
                        client.newRequest("localhost", connector.getLocalPort()).path("/prime2").send(result ->
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, result.getResponse().getStatus(), HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        });
                        break;
                    }
                    case "/prime2":
                    {
                        session2 = stream.getSession();
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        break;
                    }
                    case "/update_max_streams":
                    {
                        Session session = stream.getSession() == session1 ? session2 : session1;
                        Map<Integer, Integer> settings = new HashMap<>();
                        settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 2);
                        session.settings(new SettingsFrame(settings, false), Callback.NOOP);
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        break;
                    }
                    default:
                    {
                        sleep(processing);
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        break;
                    }
                }
                return null;
            }
        });
        http2.setMaxConcurrentStreams(1);
        prepareServer(http2);
        server.start();
        prepareClient();
        client.setMaxConnectionsPerDestination(2);
        client.start();

        // Prime the 2 connections.
        primeConnection();

        String host = "localhost";
        int port = connector.getLocalPort();

        assertEquals(1, client.getDestinations().size());
        HttpDestination destination = (HttpDestination)client.getDestinations().get(0);
        AbstractConnectionPool pool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(2, pool.getConnectionCount());

        // Send a request on one connection, which sends back a SETTINGS frame on the other connection.
        ContentResponse response = client.newRequest(host, port)
            .path("/update_max_streams")
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Send 4 requests at once: 1 should go on one connection, 2 on the other connection, and 1 queued.
        int count = 4;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            client.newRequest(host, port)
                .path("/" + i)
                .send(result ->
                {
                    if (result.isSucceeded())
                    {
                        int status = result.getResponse().getStatus();
                        if (status == HttpStatus.OK_200)
                            latch.countDown();
                        else
                            fail("unexpected status " + status);
                    }
                    else
                    {
                        fail(result.getFailure());
                    }
                });
        }

        assertTrue(awaitLatch(latch, count * processing * 10, TimeUnit.MILLISECONDS));
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

    private boolean awaitLatch(CountDownLatch latch, long time, TimeUnit unit)
    {
        try
        {
            return latch.await(time, unit);
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
