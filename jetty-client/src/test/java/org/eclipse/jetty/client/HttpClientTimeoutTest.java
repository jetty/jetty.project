//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.ssl.SslContextFactory;
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
                .send().get(timeout, TimeUnit.MILLISECONDS);
    }

    @Slow
    @Test
    public void testTimeoutOnListener() throws Exception
    {
        long timeout = 1000;
        start(new TimeoutHandler(2 * timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(timeout, TimeUnit.MILLISECONDS, new Response.Listener.Empty()
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
        client.setMaxConnectionsPerAddress(1);

        // The first request has a long timeout
        final CountDownLatch firstLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(4 * timeout, TimeUnit.MILLISECONDS, new Response.Listener.Empty()
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
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(timeout, TimeUnit.MILLISECONDS, new Response.Listener.Empty()
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

    private class TimeoutHandler extends AbstractHandler
    {
        private final long timeout;

        public TimeoutHandler(long timeout)
        {
            this.timeout = timeout;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            try
            {
                TimeUnit.MILLISECONDS.sleep(timeout);
            }
            catch (InterruptedException x)
            {
                throw new ServletException(x);
            }
        }
    }
}
