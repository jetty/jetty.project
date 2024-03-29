//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientLoadTest extends AbstractTest
{
    private final Logger logger = LoggerFactory.getLogger(HttpClientLoadTest.class);
    private final AtomicLong requestCount = new AtomicLong();

    @ParameterizedTest
    @MethodSource("transports")
    public void testIterative(Transport transport) throws Exception
    {
        server = newServer();
        start(transport, new LoadHandler());
        setStreamIdleTimeout(120000);
        client.stop();
        ArrayByteBufferPool.Tracking byteBufferPool = new ArrayByteBufferPool.Tracking();
        client.setByteBufferPool(byteBufferPool);
        client.setMaxConnectionsPerDestination(32768);
        client.setMaxRequestsQueuedPerDestination(1024 * 1024);
        client.setIdleTimeout(120000);
        client.start();

        // At least 25k requests to warmup properly (use -XX:+PrintCompilation to verify JIT activity)
        int runs = 1;
        int iterations = 100;
        for (int i = 0; i < runs; ++i)
        {
            run(transport, iterations);
        }

        // Re-run after warmup
        iterations = 250;
        for (int i = 0; i < runs; ++i)
        {
            run(transport, iterations);
        }

        assertThat("Leaks: " + byteBufferPool.dumpLeaks(), byteBufferPool.getLeaks().size(), Matchers.is(0));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrent(Transport transport) throws Exception
    {
        // TODO: cannot run HTTP/3 (or UDP) in Jenkins.
        Assumptions.assumeTrue(transport != Transport.H3);

        start(transport, new LoadHandler());
        client.stop();
        ArrayByteBufferPool.Tracking byteBufferPool = new ArrayByteBufferPool.Tracking();
        client.setByteBufferPool(byteBufferPool);
        client.setMaxConnectionsPerDestination(32768);
        client.setMaxRequestsQueuedPerDestination(1024 * 1024);
        client.start();

        int runs = 1;
        int iterations = 128;
        IntStream.range(0, 16).parallel().forEach(i ->
                IntStream.range(0, runs).forEach(j ->
                        run(transport, iterations)));

        assertThat("Connection Leaks: " + byteBufferPool.getLeaks(), byteBufferPool.getLeaks().size(), Matchers.is(0));
    }

    private void run(Transport transport, int iterations)
    {
        CountDownLatch latch = new CountDownLatch(iterations);
        List<String> failures = new ArrayList<>();

        int factor = (logger.isDebugEnabled() ? 25 : 1) * 100;

        // Dumps the state of the client if the test takes too long
        Thread testThread = Thread.currentThread();
        long maxTime = Math.max(60000, (long)iterations * factor);
        Scheduler.Task task = client.getScheduler().schedule(() ->
        {
            logger.warn("Interrupting test, it is taking too long (maxTime={} ms){}{}{}{}", maxTime,
                System.lineSeparator(), server.dump(),
                System.lineSeparator(), client.dump());
            testThread.interrupt();
        }, maxTime, TimeUnit.MILLISECONDS);

        long begin = NanoTime.now();
        for (int i = 0; i < iterations; ++i)
        {
            test(transport, latch, failures);
//            test("http", "localhost", "GET", false, false, 64 * 1024, false, latch, failures);
        }
        long end = NanoTime.now();
        task.cancel();
        long elapsed = NanoTime.millisElapsed(begin, end);
        logger.info("{} {} requests in {} ms, {} req/s", iterations, transport, elapsed, elapsed > 0 ? iterations * 1000L / elapsed : -1);

        for (String failure : failures)
        {
            logger.info("FAILED: {}", failure);
        }

        assertTrue(failures.isEmpty(), failures.toString());
    }

    private void test(Transport transport, CountDownLatch latch, List<String> failures)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Choose a random destination
        String host;
        if (transport == Transport.H3)
            host = "localhost";
        else
            host = random.nextBoolean() ? "localhost" : "127.0.0.1";
        // Choose a random method
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;

        boolean ssl = transport.isSecure();

        // Choose randomly whether to close the connection on the client or on the server
        boolean clientClose = !ssl && random.nextInt(100) < 5;
        boolean serverClose = !ssl && random.nextInt(100) < 5;

        long clientTimeout = 0;
//        if (!ssl && random.nextInt(100) < 5)
//            clientTimeout = random.nextInt(500) + 500;

        int maxContentLength = 64 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        String uri = (ssl ? "https" : "http") + "://" + host;
        if (connector instanceof NetworkConnector networkConnector)
            uri += ":" + networkConnector.getLocalPort();
        test(uri, method.asString(), clientClose, serverClose, clientTimeout, contentLength, true, latch, failures);
    }

    private void test(String uri, String method, boolean clientClose, boolean serverClose, long clientTimeout, int contentLength, boolean checkContentLength, CountDownLatch latch, List<String> failures)
    {
        long requestId = requestCount.incrementAndGet();
        Request request = client.newRequest(uri)
            .path("/" + requestId)
            .method(method);

        if (clientClose)
            request.headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE));
        else if (serverClose)
            request.headers(headers -> headers.put("X-Close", "true"));

        if (clientTimeout > 0)
        {
            request.headers(headers -> headers.put("X-Timeout", String.valueOf(clientTimeout)));
            request.idleTimeout(clientTimeout, TimeUnit.MILLISECONDS);
        }

        switch (method)
        {
            case "GET" -> request.headers(headers -> headers.put("X-Download", String.valueOf(contentLength)));
            case "POST" ->
            {
                request.headers(headers -> headers.put("X-Upload", String.valueOf(contentLength)));
                request.body(new BytesRequestContent(new byte[contentLength]));
            }
        }

        CountDownLatch requestLatch = new CountDownLatch(1);
        request.send(new Response.Listener()
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
                    Throwable failure = result.getFailure();
                    if (!(clientTimeout > 0 && failure instanceof TimeoutException))
                    {
                        failure.printStackTrace();
                        failures.add("Result failed " + result);
                    }
                }
                else
                {
                    if (checkContentLength && contentLength.get() != 0)
                        failures.add("Content length mismatch " + contentLength);
                }

                requestLatch.countDown();
                latch.countDown();
            }
        });
        int maxTime = 30000;
        if (!await(requestLatch, maxTime))
        {
            logger.warn("Request {} took too long (maxTime={} ms){}{}{}{}", requestId, maxTime,
                System.lineSeparator(), server.dump(),
                System.lineSeparator(), client.dump());
        }
    }

    private boolean await(CountDownLatch latch, long timeMs)
    {
        try
        {
            return latch.await(timeMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private static class LoadHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
        {
            String timeout = request.getHeaders().get("X-Timeout");
            if (timeout != null)
                Thread.sleep(2L * Integer.parseInt(timeout));

            if (Boolean.parseBoolean(request.getHeaders().get("X-Close")))
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

            String method = request.getMethod().toUpperCase(Locale.ENGLISH);
            switch (method)
            {
                case "GET" ->
                {
                    ByteBuffer content = BufferUtil.EMPTY_BUFFER;
                    int contentLength = (int)request.getHeaders().getLongField("X-Download");
                    if (contentLength >= 0)
                    {
                        response.getHeaders().put("X-Content", contentLength);
                        content = ByteBuffer.allocate(contentLength);
                    }
                    response.write(true, content, callback);
                }
                case "POST" ->
                {
                    response.getHeaders().put("X-Content", request.getHeaders().getLongField("X-Upload"));
                    Content.copy(request, response, callback);
                }
                default -> throw new UnsupportedOperationException();
            }
            return true;
        }
    }
}
