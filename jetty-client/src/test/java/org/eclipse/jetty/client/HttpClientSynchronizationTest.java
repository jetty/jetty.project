//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that synchronization performed from outside HttpClient does not cause deadlocks
 */
public class HttpClientSynchronizationTest extends AbstractHttpClientServerTest
{
    public HttpClientSynchronizationTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testSynchronizationOnException() throws Exception
    {
        start(new EmptyServerHandler());
        int port = connector.getLocalPort();
        server.stop();

        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", port)
                    .scheme(scheme);

            synchronized (this)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        synchronized (HttpClientSynchronizationTest.this)
                        {
                            Assert.assertThat(failure, Matchers.instanceOf(ConnectException.class));
                            latch.countDown();
                        }
                    }
                });
            }
        }

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSynchronizationOnComplete() throws Exception
    {
        start(new EmptyServerHandler());

        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme);

            synchronized (this)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        synchronized (HttpClientSynchronizationTest.this)
                        {
                            Assert.assertFalse(result.isFailed());
                            latch.countDown();
                        }
                    }
                });
            }
        }

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
