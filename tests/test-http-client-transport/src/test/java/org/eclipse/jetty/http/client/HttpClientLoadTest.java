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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.io.InterruptedIOException;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.LeakTrackingConnectionPool;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientLoadTest extends AbstractTest<HttpClientLoadTest.LoadTransportScenario>
{
    private final Logger logger = LoggerFactory.getLogger(HttpClientLoadTest.class);
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong connectionLeaks = new AtomicLong();

    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new LoadTransportScenario(transport, connectionLeaks));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIterative(Transport transport) throws Exception
    {
        // TODO: cannot run HTTP/3 (or UDP) in Jenkins.
        if ("ci".equals(System.getProperty("env")))
            Assumptions.assumeTrue(transport != Transport.H3);

        init(transport);
        scenario.start(new LoadHandler(), client ->
        {
            client.setByteBufferPool(new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));
            client.setMaxConnectionsPerDestination(32768);
            client.setMaxRequestsQueuedPerDestination(1024 * 1024);
        });
        scenario.setConnectionIdleTimeout(120000);
        scenario.setRequestIdleTimeout(120000);
        scenario.client.setIdleTimeout(120000);

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

        assertLeaks();
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConcurrent(Transport transport) throws Exception
    {
        // TODO: cannot run HTTP/3 (or UDP) in Jenkins.
        Assumptions.assumeTrue(transport != Transport.H3);

        init(transport);
        scenario.start(new LoadHandler(), client ->
        {
            client.setByteBufferPool(new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));
            client.setMaxConnectionsPerDestination(32768);
            client.setMaxRequestsQueuedPerDestination(1024 * 1024);
        });

        int runs = 1;
        int iterations = 128;
        IntStream.range(0, 16).parallel().forEach(i ->
            IntStream.range(0, runs).forEach(j ->
                run(transport, iterations)));

        assertLeaks();
    }

    private void assertLeaks()
    {
        System.gc();

        ByteBufferPool byteBufferPool = scenario.connector.getByteBufferPool();
        if (byteBufferPool instanceof LeakTrackingByteBufferPool)
        {
            LeakTrackingByteBufferPool serverBufferPool = (LeakTrackingByteBufferPool)byteBufferPool;
            assertThat("Server BufferPool - leaked acquires", serverBufferPool.getLeakedAcquires(), Matchers.is(0L));
            assertThat("Server BufferPool - leaked releases", serverBufferPool.getLeakedReleases(), Matchers.is(0L));
            assertThat("Server BufferPool - leaked removes", serverBufferPool.getLeakedRemoves(), Matchers.is(0L));
            assertThat("Server BufferPool - unreleased", serverBufferPool.getLeakedResources(), Matchers.is(0L));
        }

        byteBufferPool = scenario.client.getByteBufferPool();
        if (byteBufferPool instanceof LeakTrackingByteBufferPool)
        {
            LeakTrackingByteBufferPool clientBufferPool = (LeakTrackingByteBufferPool)byteBufferPool;
            assertThat("Client BufferPool - leaked acquires", clientBufferPool.getLeakedAcquires(), Matchers.is(0L));
            assertThat("Client BufferPool - leaked releases", clientBufferPool.getLeakedReleases(), Matchers.is(0L));
            assertThat("Client BufferPool - leaked removes", clientBufferPool.getLeakedRemoves(), Matchers.is(0L));
            assertThat("Client BufferPool - unreleased", clientBufferPool.getLeakedResources(), Matchers.is(0L));
        }

        assertThat("Connection Leaks", connectionLeaks.get(), Matchers.is(0L));
    }

    private void run(Transport transport, int iterations)
    {
        CountDownLatch latch = new CountDownLatch(iterations);
        List<String> failures = new ArrayList<>();

        int factor = (logger.isDebugEnabled() ? 25 : 1) * 100;

        // Dumps the state of the client if the test takes too long
        Thread testThread = Thread.currentThread();
        long maxTime = Math.max(60000, (long)iterations * factor);
        Scheduler.Task task = scenario.client.getScheduler().schedule(() ->
        {
            logger.warn("Interrupting test, it is taking too long (maxTime={} ms){}{}{}{}", maxTime,
                System.lineSeparator(), scenario.server.dump(),
                System.lineSeparator(), scenario.client.dump());
            testThread.interrupt();
        }, maxTime, TimeUnit.MILLISECONDS);

        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            test(latch, failures);
//            test("http", "localhost", "GET", false, false, 64 * 1024, false, latch, failures);
        }
        long end = System.nanoTime();
        task.cancel();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
        logger.info("{} {} requests in {} ms, {} req/s", iterations, transport, elapsed, elapsed > 0 ? iterations * 1000L / elapsed : -1);

        for (String failure : failures)
        {
            logger.info("FAILED: {}", failure);
        }

        assertTrue(failures.isEmpty(), failures.toString());
    }

    private void test(CountDownLatch latch, List<String> failures)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Choose a random destination
        String host = random.nextBoolean() ? "localhost" : "127.0.0.1";
        // Choose a random method
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;

        boolean ssl = scenario.transport.isTlsBased();

        // Choose randomly whether to close the connection on the client or on the server
        boolean clientClose = !ssl && random.nextInt(100) < 5;
        boolean serverClose = !ssl && random.nextInt(100) < 5;

        long clientTimeout = 0;
//        if (!ssl && random.nextInt(100) < 5)
//            clientTimeout = random.nextInt(500) + 500;

        int maxContentLength = 64 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        test(scenario.getScheme(), host, method.asString(), clientClose, serverClose, clientTimeout, contentLength, true, latch, failures);
    }

    private void test(String scheme, String host, String method, boolean clientClose, boolean serverClose, long clientTimeout, int contentLength, boolean checkContentLength, CountDownLatch latch, List<String> failures)
    {
        long requestId = requestCount.incrementAndGet();
        Request request = scenario.client.newRequest(host, scenario.getServerPort().orElse(0))
            .scheme(scheme)
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
            case "GET":
                request.headers(headers -> headers.put("X-Download", String.valueOf(contentLength)));
                break;
            case "POST":
                request.headers(headers -> headers.put("X-Upload", String.valueOf(contentLength)));
                request.body(new BytesRequestContent(new byte[contentLength]));
                break;
        }

        CountDownLatch requestLatch = new CountDownLatch(1);
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
        if (!await(requestLatch, maxTime, TimeUnit.MILLISECONDS))
        {
            logger.warn("Request {} took too long (maxTime={} ms){}{}{}{}", requestId, maxTime,
                System.lineSeparator(), scenario.server.dump(),
                System.lineSeparator(), scenario.client.dump());
        }
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

    private static class LoadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);

            String timeout = request.getHeader("X-Timeout");
            if (timeout != null)
                sleep(2L * Integer.parseInt(timeout));

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
        }

        private void sleep(long time) throws InterruptedIOException
        {
            try
            {
                Thread.sleep(time);
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }
    }

    public static class LoadTransportScenario extends TransportScenario
    {
        private final AtomicLong connectionLeaks;

        public LoadTransportScenario(Transport transport, AtomicLong connectionLeaks) throws IOException
        {
            super(transport);
            this.connectionLeaks = connectionLeaks;
        }

        @Override
        public Connector newServerConnector(Server server)
        {
            int selectors = Math.min(1, ProcessorUtils.availableProcessors() / 2);
            ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
            byteBufferPool = new LeakTrackingByteBufferPool(byteBufferPool);
            switch (transport)
            {
                case HTTP:
                case HTTPS:
                case H2C:
                case H2:
                case FCGI:
                    return new ServerConnector(server, null, null, byteBufferPool, 1, selectors, provideServerConnectionFactory(transport));
                case H3:
                    return new HTTP3ServerConnector(server, null, null, byteBufferPool, sslContextFactory, provideServerConnectionFactory(transport));
                case UNIX_DOMAIN:
                    UnixDomainServerConnector unixSocketConnector = new UnixDomainServerConnector(server, null, null, byteBufferPool, 1, selectors, provideServerConnectionFactory(transport));
                    unixSocketConnector.setUnixDomainPath(unixDomainPath);
                    return unixSocketConnector;
                default:
                    throw new IllegalStateException();
            }
        }

        @Override
        public HttpClientTransport provideClientTransport(Transport transport, SslContextFactory.Client sslContextFactory)
        {
            HttpClientTransport clientTransport = super.provideClientTransport(transport, sslContextFactory);
            switch (transport)
            {
                case HTTP:
                case HTTPS:
                case FCGI:
                case UNIX_DOMAIN:
                {
                    // Track connection leaking only for non-multiplexed transports.
                    clientTransport.setConnectionPoolFactory(destination -> new LeakTrackingConnectionPool(destination, client.getMaxConnectionsPerDestination(), destination)
                    {
                        @Override
                        protected void leaked(LeakDetector<Connection>.LeakInfo leakInfo)
                        {
                            super.leaked(leakInfo);
                            connectionLeaks.incrementAndGet();
                        }
                    });
                    break;
                }
                default:
                {
                    break;
                }
            }
            return clientTransport;
        }
    }
}
