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

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalSiteTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();

    private HttpClient client;

    @Before
    public void prepare() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        client.stop();
    }

    @Test
    public void testExternalSite() throws Exception
    {
        String host = "wikipedia.org";
        int port = 80;

        // Verify that we have connectivity
        try
        {
            new Socket(host, port);
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }

        final CountDownLatch latch1 = new CountDownLatch(1);
        client.newRequest(host, port).send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                if (!result.isFailed() && result.getResponse().getStatus() == 200)
                    latch1.countDown();
            }
        });
        Assert.assertTrue(latch1.await(10, TimeUnit.SECONDS));

        // Try again the same URI, but without specifying the port
        final CountDownLatch latch2 = new CountDownLatch(1);
        client.newRequest("http://" + host).send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertTrue(result.isSucceeded());
                Assert.assertEquals(200, result.getResponse().getStatus());
                URI uri = result.getRequest().getURI();
                Assert.assertTrue(uri.getPort() > 0);
                latch2.countDown();
            }
        });
        Assert.assertTrue(latch2.await(10, TimeUnit.SECONDS));
    }


    @Test
    public void testExternalSiteWrongProtocol() throws Exception
    {
        String host = "github.com";
        int port = 22; // SSH port

        // Verify that we have connectivity
        try
        {
            new Socket(host, port);
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }

        for (int i = 0; i < 2; ++i)
        {
            final CountDownLatch latch = new CountDownLatch(3);
            client.newRequest(host, port)
                    .onResponseFailure(new Response.FailureListener()
                    {
                        @Override
                        public void onFailure(Response response, Throwable failure)
                        {
                            latch.countDown();
                        }
                    })
                    .send(new Response.Listener.Empty()
                    {
                        @Override
                        public void onFailure(Response response, Throwable failure)
                        {
                            latch.countDown();
                        }

                        @Override
                        public void onComplete(Result result)
                        {
                            Assert.assertTrue(result.isFailed());
                            latch.countDown();
                        }
                    });
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
    }
}
