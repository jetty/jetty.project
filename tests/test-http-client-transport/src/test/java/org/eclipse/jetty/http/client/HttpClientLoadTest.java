//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.LeakTrackingConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.client.http.HttpDestinationOverFCGI;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertThat;

public class HttpClientLoadTest extends AbstractTest
{
    private final Logger logger = Log.getLogger(HttpClientLoadTest.class);
    private final AtomicLong connectionLeaks = new AtomicLong();

    public HttpClientLoadTest(Transport transport)
    {
        super(transport);
    }

    @Override
    protected ServerConnector newServerConnector(Server server)
    {
        int cores = Runtime.getRuntime().availableProcessors();
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        byteBufferPool = new LeakTrackingByteBufferPool(byteBufferPool);
        return new ServerConnector(server, null, null, byteBufferPool,
                1, Math.min(1, cores / 2), provideServerConnectionFactory(transport));
    }

    @Override
    protected HttpClientTransport provideClientTransport(Transport transport)
    {
        switch (transport)
        {
            case HTTP:
            case HTTPS:
            {
                return new HttpClientTransportOverHTTP(1)
                {
                    @Override
                    public HttpDestination newHttpDestination(Origin origin)
                    {
                        return new HttpDestinationOverHTTP(getHttpClient(), origin)
                        {
                            @Override
                            protected ConnectionPool newConnectionPool(HttpClient client)
                            {
                                return new LeakTrackingConnectionPool(this, client.getMaxConnectionsPerDestination(), this)
                                {
                                    @Override
                                    protected void leaked(LeakDetector.LeakInfo leakInfo)
                                    {
                                        super.leaked(leakInfo);
                                        connectionLeaks.incrementAndGet();
                                    }
                                };
                            }
                        };
                    }
                };
            }
            case FCGI:
            {
                return new HttpClientTransportOverFCGI(1, false, "")
                {
                    @Override
                    public HttpDestination newHttpDestination(Origin origin)
                    {
                        return new HttpDestinationOverFCGI(getHttpClient(), origin)
                        {
                            @Override
                            protected ConnectionPool newConnectionPool(HttpClient client)
                            {
                                return new LeakTrackingConnectionPool(this, client.getMaxConnectionsPerDestination(), this)
                                {
                                    @Override
                                    protected void leaked(LeakDetector.LeakInfo leakInfo)
                                    {
                                        super.leaked(leakInfo);
                                        connectionLeaks.incrementAndGet();
                                    }
                                };
                            }
                        };
                    }
                };
            }
            default:
            {
                return super.provideClientTransport(transport);
            }
        }
    }

    @Test
    public void testIterative() throws Exception
    {
        start(new LoadHandler());

        client.setByteBufferPool(new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));
        client.setMaxConnectionsPerDestination(32768);
        client.setMaxRequestsQueuedPerDestination(1024 * 1024);

        Random random = new Random();
        // At least 25k requests to warmup properly (use -XX:+PrintCompilation to verify JIT activity)
        int runs = 1;
        int iterations = 500;
        for (int i = 0; i < runs; ++i)
        {
            run(random, iterations);
        }

        // Re-run after warmup
        iterations = 5_000;
        for (int i = 0; i < runs; ++i)
        {
            run(random, iterations);
        }

        System.gc();

        ByteBufferPool byteBufferPool = connector.getByteBufferPool();
        if (byteBufferPool instanceof LeakTrackingByteBufferPool)
        {
            LeakTrackingByteBufferPool serverBufferPool = (LeakTrackingByteBufferPool)byteBufferPool;
            assertThat("Server BufferPool - leaked acquires", serverBufferPool.getLeakedAcquires(), Matchers.is(0L));
            assertThat("Server BufferPool - leaked releases", serverBufferPool.getLeakedReleases(), Matchers.is(0L));
            assertThat("Server BufferPool - unreleased", serverBufferPool.getLeakedResources(), Matchers.is(0L));
        }

        byteBufferPool = client.getByteBufferPool();
        if (byteBufferPool instanceof LeakTrackingByteBufferPool)
        {
            LeakTrackingByteBufferPool clientBufferPool = (LeakTrackingByteBufferPool)byteBufferPool;
            assertThat("Client BufferPool - leaked acquires", clientBufferPool.getLeakedAcquires(), Matchers.is(0L));
            assertThat("Client BufferPool - leaked releases", clientBufferPool.getLeakedReleases(), Matchers.is(0L));
            assertThat("Client BufferPool - unreleased", clientBufferPool.getLeakedResources(), Matchers.is(0L));
        }

        assertThat("Connection Leaks", connectionLeaks.get(), Matchers.is(0L));
    }

    @Test
    public void testConcurrent() throws Exception
    {
        start(new LoadHandler());

        Random random = new Random();
        int runs = 1;
        int iterations = 256;
        IntStream.range(0, 16).parallel().forEach(i ->
                IntStream.range(0, runs).forEach(j ->
                        run(random, iterations)));
    }

    private void run(Random random, int iterations)
    {
        CountDownLatch latch = new CountDownLatch(iterations);
        List<String> failures = new ArrayList<>();

        int factor = (logger.isDebugEnabled() ? 25 : 1) * 100;

        // Dumps the state of the client if the test takes too long
        final Thread testThread = Thread.currentThread();
        Scheduler.Task task = client.getScheduler().schedule(() ->
        {
            logger.warn("Interrupting test, it is taking too long");
            logger.warn(client.dump());
            testThread.interrupt();
        }, iterations * factor, TimeUnit.MILLISECONDS);

        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            test(random, latch, failures);
//            test("http", "localhost", "GET", false, false, 64 * 1024, false, latch, failures);
        }
        Assert.assertTrue(await(latch, iterations, TimeUnit.SECONDS));
        long end = System.nanoTime();
        task.cancel();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
        logger.info("{} requests in {} ms, {} req/s", iterations, elapsed, elapsed > 0 ? iterations * 1000 / elapsed : -1);

        for (String failure : failures)
            System.err.println("FAILED: "+failure);

        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    private void test(Random random, final CountDownLatch latch, final List<String> failures)
    {
        // Choose a random destination
        String host = random.nextBoolean() ? "localhost" : "127.0.0.1";
        // Choose a random method
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;

        boolean ssl = isTransportSecure();

        // Choose randomly whether to close the connection on the client or on the server
        boolean clientClose = false;
        if (!ssl && random.nextBoolean())
            clientClose = true;
        boolean serverClose = false;
        if (!ssl && random.nextBoolean())
            serverClose = true;

        int maxContentLength = 64 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        test(ssl ? "https" : "http", host, method.asString(), clientClose, serverClose, contentLength, true, latch, failures);
    }

    private void test(String scheme, String host, String method, boolean clientClose, boolean serverClose, int contentLength, final boolean checkContentLength, final CountDownLatch latch, final List<String> failures)
    {
        Request request = client.newRequest(host, connector.getLocalPort())
                .scheme(scheme)
                .method(method);

        if (clientClose)
            request.header(HttpHeader.CONNECTION, "close");
        else if (serverClose)
            request.header("X-Close", "true");

        switch (method)
        {
            case "GET":
                request.header("X-Download", String.valueOf(contentLength));
                break;
            case "POST":
                request.header("X-Upload", String.valueOf(contentLength));
                request.content(new BytesContentProvider(new byte[contentLength]));
                break;
        }

        final CountDownLatch requestLatch = new CountDownLatch(1);
        request.send(new Response.Listener.Adapter()
        {
            private final AtomicInteger contentLength = new AtomicInteger();

            @Override
            public void onHeaders(Response response)
            {
                if (checkContentLength)
                {
                    String content = response.getHeaders().get("X-Content");
                    if (content != null)
                        contentLength.set(Integer.parseInt(content));
                }
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                if (checkContentLength)
                    contentLength.addAndGet(-content.remaining());
            }

            @Override
            public void onComplete(Result result)
            {
                if (result.isFailed())
                {
                    result.getFailure().printStackTrace();
                    failures.add("Result failed " + result);
                }

                if (checkContentLength && contentLength.get() != 0)
                    failures.add("Content length mismatch " + contentLength);

                requestLatch.countDown();
                latch.countDown();
            }
        });
        await(requestLatch, 5, TimeUnit.SECONDS);
    }

    private boolean await(CountDownLatch latch, long time, TimeUnit unit)
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

    private class LoadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            String method = request.getMethod().toUpperCase(Locale.ENGLISH);
            switch (method)
            {
                case "GET":
                {
                    int contentLength = request.getIntHeader("X-Download");
                    if (contentLength > 0)
                    {
                        response.setHeader("X-Content", String.valueOf(contentLength));
                        response.getOutputStream().write(new byte[contentLength]);
                    }
                    break;
                }
                case "POST":
                {
                    response.setHeader("X-Content", request.getHeader("X-Upload"));
                    IO.copy(request.getInputStream(), response.getOutputStream());
                    break;
                }
            }

            if (Boolean.parseBoolean(request.getHeader("X-Close")))
                response.setHeader("Connection", "close");

            baseRequest.setHandled(true);
        }
    }
}
