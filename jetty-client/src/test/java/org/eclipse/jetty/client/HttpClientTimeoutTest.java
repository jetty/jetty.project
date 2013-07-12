//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLEngine;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientTimeoutTest extends AbstractHttpClientServerTest
{
    public HttpClientTimeoutTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Slow
    @Test(expected = TimeoutException.class)
    public void testTimeoutOnFuture() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send();
    }

    @Slow
    @Test
    public void testTimeoutOnListener() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertTrue(result.isFailed());
                latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
    }

    @Slow
    @Test
    public void testTimeoutOnQueuedRequest() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(3 * timeout));

        // Only one connection so requests get queued
        client.setMaxConnectionsPerDestination(1);

        // The first request has a long timeout
        final CountDownLatch firstLatch = new CountDownLatch(1);
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(4 * timeout, TimeUnit.MILLISECONDS);
        request.send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertFalse(result.isFailed());
                firstLatch.countDown();
            }
        });

        // Second request has a short timeout and should fail in the queue
        final CountDownLatch secondLatch = new CountDownLatch(1);
        request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertTrue(result.isFailed());
                secondLatch.countDown();
            }
        });

        Assert.assertTrue(secondLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
        // The second request must fail before the first request has completed
        Assert.assertTrue(firstLatch.getCount() > 0);
        Assert.assertTrue(firstLatch.await(5 * timeout, TimeUnit.MILLISECONDS));
    }

    @Slow
    @Test
    public void testTimeoutIsCancelledOnSuccess() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
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

    @Slow
    @Test
    public void testTimeoutOnListenerWithExplicitConnection() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .timeout(timeout, TimeUnit.MILLISECONDS);
            connection.send(request, new Response.CompleteListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    Assert.assertTrue(result.isFailed());
                    latch.countDown();
                }
            });

            Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));
        }
    }

    @Slow
    @Test
    public void testTimeoutIsCancelledOnSuccessWithExplicitConnection() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(destination.getHost(), destination.getPort())
                    .scheme(scheme)
                    .timeout(2 * timeout, TimeUnit.MILLISECONDS);
            connection.send(request, new Response.CompleteListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    Response response = result.getResponse();
                    Assert.assertEquals(200, response.getStatus());
                    Assert.assertFalse(result.isFailed());
                    latch.countDown();
                }
            });

            Assert.assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

            TimeUnit.MILLISECONDS.sleep(2 * timeout);

            Assert.assertNull(request.getAbortCause());
        }
    }

    @Test
    public void testIdleTimeout() throws Throwable
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));
        client.stop();
        final AtomicBoolean sslIdle = new AtomicBoolean();
        client = new HttpClient(new HttpClientTransportOverHTTP()
        {
            @Override
            protected SslConnection newSslConnection(HttpClient httpClient, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(httpClient.getByteBufferPool(), httpClient.getExecutor(), endPoint, engine)
                {
                    @Override
                    protected boolean onReadTimeout()
                    {
                        sslIdle.set(true);
                        return super.onReadTimeout();
                    }
                };
            }
        }, sslContextFactory);
        client.setIdleTimeout(timeout);
        client.start();

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .send();
            Assert.fail();
        }
        catch (Exception x)
        {
            Assert.assertFalse(sslIdle.get());
            Assert.assertThat(x.getCause(), Matchers.instanceOf(TimeoutException.class));
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
