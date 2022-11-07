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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLEngine;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.opentest4j.TestAbortedException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpClientTimeoutTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutOnFuture(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(2 * timeout));

        assertThrows(TimeoutException.class, () ->
        {
            scenario.client.newRequest(scenario.newURI())
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send();
        });
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutOnListener(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(2 * timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = scenario.client.newRequest(scenario.newURI())
            .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertTrue(result.isFailed());
            latch.countDown();
        });
        assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutOnQueuedRequest(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(3 * timeout));

        // Only one connection so requests get queued
        scenario.client.setMaxConnectionsPerDestination(1);

        // The first request has a long timeout
        final CountDownLatch firstLatch = new CountDownLatch(1);
        Request request = scenario.client.newRequest(scenario.newURI())
            .timeout(4 * timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertFalse(result.isFailed());
            firstLatch.countDown();
        });

        // Second request has a short timeout and should fail in the queue
        final CountDownLatch secondLatch = new CountDownLatch(1);
        request = scenario.client.newRequest(scenario.newURI())
            .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertTrue(result.isFailed());
            secondLatch.countDown();
        });

        assertTrue(secondLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
        // The second request must fail before the first request has completed
        assertTrue(firstLatch.getCount() > 0);
        assertTrue(firstLatch.await(5 * timeout, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutIsCancelledOnSuccess(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Request request = scenario.client.newRequest(scenario.newURI())
            .body(new InputStreamRequestContent(new ByteArrayInputStream(content)))
            .timeout(2 * timeout, TimeUnit.MILLISECONDS);
        request.send(new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertFalse(result.isFailed());
                assertArrayEquals(content, getContent());
                latch.countDown();
            }
        });

        assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

        TimeUnit.MILLISECONDS.sleep(2 * timeout);

        assertNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutOnListenerWithExplicitConnection(Transport transport) throws Exception
    {
        init(transport);

        long timeout = 1000;
        scenario.start(new TimeoutHandler(2 * timeout));

        Request request = scenario.client.newRequest(scenario.newURI()).timeout(timeout, TimeUnit.MILLISECONDS);
        CountDownLatch latch = new CountDownLatch(1);
        Destination destination = scenario.client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            connection.send(request, result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });

            assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutIsCancelledOnSuccessWithExplicitConnection(Transport transport) throws Exception
    {
        init(transport);

        long timeout = 1000;
        scenario.start(new TimeoutHandler(timeout));

        Request request = scenario.client.newRequest(scenario.newURI()).timeout(2 * timeout, TimeUnit.MILLISECONDS);
        CountDownLatch latch = new CountDownLatch(1);
        Destination destination = scenario.client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            connection.send(request, result ->
            {
                Response response = result.getResponse();
                assertEquals(200, response.getStatus());
                assertFalse(result.isFailed());
                latch.countDown();
            });

            assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

            TimeUnit.MILLISECONDS.sleep(2 * timeout);

            assertNull(request.getAbortCause());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.startServer(new TimeoutHandler(2 * timeout));

        AtomicBoolean sslIdle = new AtomicBoolean();
        SslContextFactory.Client sslContextFactory = scenario.newClientSslContextFactory();
        scenario.client = new HttpClient(scenario.provideClientTransport(transport, sslContextFactory))
        {
            @Override
            public ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory.Client sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine)
                        {
                            @Override
                            protected boolean onReadTimeout(Throwable timeout)
                            {
                                sslIdle.set(true);
                                return super.onReadTimeout(timeout);
                            }
                        };
                    }
                };
            }
        };
        scenario.client.setIdleTimeout(timeout);
        scenario.client.start();

        assertThrows(TimeoutException.class, () ->
        {
            scenario.client.newRequest(scenario.newURI())
                .send();
        });
        assertFalse(sslIdle.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingConnectTimeoutFailsRequest(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocket address failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);
        init(transport);
        testConnectTimeoutFailsRequest(true);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testNonBlockingConnectTimeoutFailsRequest(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocket address failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);
        init(transport);
        testConnectTimeoutFailsRequest(false);
    }

    private void testConnectTimeoutFailsRequest(boolean blocking) throws Exception
    {
        // Using IANA hosted example.com:81 to reliably produce a Connect Timeout.
        final String host = "example.com";
        final int port = 81;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        HttpClient client = scenario.client;
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.setConnectBlocking(blocking);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.scheme(scenario.getScheme())
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocket address failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);

        init(transport);

        // Using IANA hosted example.com:81 to reliably produce a Connect Timeout.
        final String host = "example.com";
        final int port = 81;
        int connectTimeout = 2000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        HttpClient client = scenario.client;
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        Request request = client.newRequest(host, port);
        request.scheme(scenario.getScheme())
            .timeout(connectTimeout / 2, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                completes.incrementAndGet();
                latch.countDown();
            });

        assertFalse(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertEquals(1, completes.get());
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRetryAfterConnectTimeout(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocket address failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);

        init(transport);

        // Using IANA hosted example.com:81 to reliably produce a Connect Timeout.
        final String host = "example.com";
        final int port = 81;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        HttpClient client = scenario.client;
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.scheme(scenario.getScheme())
            .send(result ->
            {
                if (result.isFailed())
                {
                    // Retry
                    client.newRequest(host, port)
                        .scheme(scenario.getScheme())
                        .send(retryResult ->
                        {
                            if (retryResult.isFailed())
                                latch.countDown();
                        });
                }
            });

        assertTrue(latch.await(3 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testVeryShortTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .timeout(1, TimeUnit.MILLISECONDS) // Very short timeout
            .send(result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutCancelledWhenSendingThrowsException(Transport transport) throws Exception
    {
        init(transport);

        scenario.start(new EmptyServerHandler());

        long timeout = 1000;
        String uri = "badscheme://0.0.0.1";
        if (scenario.getServerPort().isPresent())
            uri += ":" + scenario.getServerPort().getAsInt();
        Request request = scenario.client.newRequest(uri);

        // TODO: assert a more specific Throwable
        assertThrows(Exception.class, () ->
        {
            request.timeout(timeout, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                });
        });

        Thread.sleep(2 * timeout);

        // If the task was not cancelled, it aborted the request.
        assertNull(request.getAbortCause());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testFirstRequestTimeoutAfterSecondRequestCompletes(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 2000;
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().startsWith("/one"))
                {
                    try
                    {
                        Thread.sleep(3 * timeout);
                    }
                    catch (InterruptedException x)
                    {
                        throw new InterruptedIOException();
                    }
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/one")
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed() && result.getFailure() instanceof TimeoutException)
                    latch.countDown();
            });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .path("/two")
            .timeout(timeout, TimeUnit.MILLISECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRequestQueuedDoesNotCancelTimeoutOfQueuedRequests(Transport transport) throws Exception
    {
        init(transport);

        CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().startsWith("/one"))
                {
                    try
                    {
                        serverLatch.await();
                    }
                    catch (InterruptedException x)
                    {
                        throw new InterruptedIOException();
                    }
                }
            }
        });

        scenario.client.setMaxConnectionsPerDestination(1);
        scenario.setMaxRequestsPerConnection(1);

        // Send the first request so that the others get queued.
        CountDownLatch latch1 = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/one")
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                latch1.countDown();
            });

        // Queue a second request, it should expire in the queue.
        long timeout = 1000;
        CountDownLatch latch2 = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/two")
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                assertTrue(result.isFailed());
                assertThat(result.getFailure(), Matchers.instanceOf(TimeoutException.class));
                latch2.countDown();
            });

        Thread.sleep(timeout);

        // Queue a third request, it should not reset the timeout of the second request.
        CountDownLatch latch3 = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/three")
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                latch3.countDown();
            });

        // We have already slept a timeout, expect the second request to be back in another timeout.
        assertTrue(latch2.await(2 * timeout, TimeUnit.MILLISECONDS));

        // Release the first request so the third can be served as well.
        serverLatch.countDown();

        assertTrue(latch1.await(2 * timeout, TimeUnit.MILLISECONDS));
        assertTrue(latch3.await(2 * timeout, TimeUnit.MILLISECONDS));
    }

    private void assumeConnectTimeout(String host, int port, int connectTimeout)
    {
        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            // Fail the test if we can connect.
            fail("Error: Should not have been able to connect to " + host + ":" + port);
        }
        catch (SocketTimeoutException ignored)
        {
            // Expected timeout during connect, continue the test.
        }
        catch (Throwable x)
        {
            // Abort if any other exception happens.
            throw new TestAbortedException("Not able to validate connect timeout conditions", x);
        }
    }

    private static class TimeoutHandler extends AbstractHandler
    {
        private final long timeout;

        public TimeoutHandler(long timeout)
        {
            this.timeout = timeout;
        }

        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            try
            {
                TimeUnit.MILLISECONDS.sleep(timeout);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
            catch (InterruptedException x)
            {
                throw new ServletException(x);
            }
        }
    }
}
