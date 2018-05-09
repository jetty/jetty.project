//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class HttpClientTimeoutTest extends AbstractTest
{
    public HttpClientTimeoutTest(Transport transport)
    {
        super(transport);
    }

    @Test(expected = TimeoutException.class)
    public void testTimeoutOnFuture() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        client.newRequest(newURI())
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send();
    }

    @Test
    public void testTimeoutOnListener() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(newURI())
                .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            Assert.assertTrue(result.isFailed());
            latch.countDown();
        });
        Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testTimeoutOnQueuedRequest() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(3 * timeout));

        // Only one connection so requests get queued
        client.setMaxConnectionsPerDestination(1);

        // The first request has a long timeout
        final CountDownLatch firstLatch = new CountDownLatch(1);
        Request request = client.newRequest(newURI())
                .timeout(4 * timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            Assert.assertFalse(result.isFailed());
            firstLatch.countDown();
        });

        // Second request has a short timeout and should fail in the queue
        final CountDownLatch secondLatch = new CountDownLatch(1);
        request = client.newRequest(newURI())
                .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            Assert.assertTrue(result.isFailed());
            secondLatch.countDown();
        });

        Assert.assertTrue(secondLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
        // The second request must fail before the first request has completed
        Assert.assertTrue(firstLatch.getCount() > 0);
        Assert.assertTrue(firstLatch.await(5 * timeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testTimeoutIsCancelledOnSuccess() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Request request = client.newRequest(newURI())
                .content(new InputStreamContentProvider(new ByteArrayInputStream(content)))
                .timeout(2 * timeout, TimeUnit.MILLISECONDS);
        request.send(new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertFalse(result.isFailed());
                Assert.assertArrayEquals(content, getContent());
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

        TimeUnit.MILLISECONDS.sleep(2 * timeout);

        Assert.assertNull(request.getAbortCause());
    }

    @Test
    public void testTimeoutOnListenerWithExplicitConnection() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));
        Assume.assumeTrue(connector instanceof NetworkConnector);

        final CountDownLatch latch = new CountDownLatch(1);
        Destination destination = client.getDestination(getScheme(), "localhost", ((NetworkConnector)connector).getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(newURI())
                    .timeout(timeout, TimeUnit.MILLISECONDS);
            connection.send(request, result ->
            {
                Assert.assertTrue(result.isFailed());
                latch.countDown();
            });

            Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testTimeoutIsCancelledOnSuccessWithExplicitConnection() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(timeout));
        Assume.assumeTrue(connector instanceof NetworkConnector);

        final CountDownLatch latch = new CountDownLatch(1);
        Destination destination = client.getDestination(getScheme(), "localhost", ((NetworkConnector)connector).getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(newURI())
                    .timeout(2 * timeout, TimeUnit.MILLISECONDS);
            connection.send(request, result ->
            {
                Response response = result.getResponse();
                Assert.assertEquals(200, response.getStatus());
                Assert.assertFalse(result.isFailed());
                latch.countDown();
            });

            Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

            TimeUnit.MILLISECONDS.sleep(2 * timeout);

            Assert.assertNull(request.getAbortCause());
        }
    }

    @Test(expected = TimeoutException.class)
    public void testIdleTimeout() throws Throwable
    {
        long timeout = 1000;
        startServer(new TimeoutHandler(2 * timeout));

        AtomicBoolean sslIdle = new AtomicBoolean();
        client = new HttpClient(provideClientTransport(transport), sslContextFactory)
        {
            @Override
            public ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
            {
                return new SslClientConnectionFactory(getSslContextFactory(), getByteBufferPool(), getExecutor(), connectionFactory)
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
        client.setIdleTimeout(timeout);
        client.start();

        try
        {
            client.newRequest(newURI())
                    .send();
            Assert.fail();
        }
        catch (Exception x)
        {
            Assert.assertFalse(sslIdle.get());
            throw x;
        }
    }

    @Test
    public void testBlockingConnectTimeoutFailsRequest() throws Exception
    {
        testConnectTimeoutFailsRequest(true);
    }

    @Test
    public void testNonBlockingConnectTimeoutFailsRequest() throws Exception
    {
        testConnectTimeoutFailsRequest(false);
    }

    private void testConnectTimeoutFailsRequest(boolean blocking) throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        Assume.assumeTrue(connector instanceof NetworkConnector);
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.setConnectBlocking(blocking);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.scheme(getScheme())
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        Assert.assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(request.getAbortCause());
    }

    @Test
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout() throws Exception
    {
        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 2000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        Assume.assumeTrue(connector instanceof NetworkConnector);
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        Request request = client.newRequest(host, port);
        request.scheme(getScheme())
                .timeout(connectTimeout / 2, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                    completes.incrementAndGet();
                    latch.countDown();
                });

        Assert.assertFalse(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(1, completes.get());
        Assert.assertNotNull(request.getAbortCause());
    }

    @Test
    public void retryAfterConnectTimeout() throws Exception
    {
        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new EmptyServerHandler());
        Assume.assumeTrue(connector instanceof NetworkConnector);
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.scheme(getScheme())
                .send(result ->
                {
                    if (result.isFailed())
                    {
                        // Retry
                        client.newRequest(host, port)
                                .scheme(getScheme())
                                .send(retryResult ->
                                {
                                    if (retryResult.isFailed())
                                        latch.countDown();
                                });
                    }
                });

        Assert.assertTrue(latch.await(333 * connectTimeout, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(request.getAbortCause());
    }

    @Test
    public void testVeryShortTimeout() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI())
                .timeout(1, TimeUnit.MILLISECONDS) // Very short timeout
                .send(result -> latch.countDown());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testTimeoutCancelledWhenSendingThrowsException() throws Exception
    {
        start(new EmptyServerHandler());
        Assume.assumeTrue(connector instanceof NetworkConnector);

        long timeout = 1000;
        Request request = client.newRequest("badscheme://localhost:" + ((NetworkConnector)connector).getLocalPort());

        try
        {
            request.timeout(timeout, TimeUnit.MILLISECONDS)
                    .send(result -> {});
            Assert.fail();
        }
        catch (Exception ignored)
        {
        }

        Thread.sleep(2 * timeout);

        // If the task was not cancelled, it aborted the request.
        Assert.assertNull(request.getAbortCause());
    }

    @Test
    public void testFirstRequestTimeoutAfterSecondRequestCompletes() throws Exception
    {
        long timeout = 2000;
        start(new EmptyServerHandler()
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
        client.newRequest(newURI())
                .path("/one")
                .timeout(2 * timeout, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                    if (result.isFailed() && result.getFailure() instanceof TimeoutException)
                        latch.countDown();
                });

        ContentResponse response = client.newRequest(newURI())
                .path("/two")
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
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
            // Abort the test if we can connect.
            Assume.assumeTrue(false);
        }
        catch (SocketTimeoutException x)
        {
            // Expected timeout during connect, continue the test.
            Assume.assumeTrue(true);
        }
        catch (Throwable x)
        {
            // Abort if any other exception happens.
            Assume.assumeTrue(false);
        }
    }

    private class TimeoutHandler extends AbstractHandler
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
