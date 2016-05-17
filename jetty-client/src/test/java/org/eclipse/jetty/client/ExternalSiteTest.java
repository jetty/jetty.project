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

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore
public class ExternalSiteTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();

    private HttpClient client;

    @Before
    public void prepare() throws Exception
    {
        client = new HttpClient(new SslContextFactory());
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
        assumeCanConnectTo(host, port);

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
                latch2.countDown();
            }
        });
        Assert.assertTrue(latch2.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testExternalSSLSite() throws Exception
    {
        client.stop();
        client = new HttpClient(new SslContextFactory());
        client.start();

        String host = "api-3t.paypal.com";
        int port = 443;

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port).scheme("https").path("/nvp").send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                    latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExternalSiteWrongProtocol() throws Exception
    {
        String host = "github.com";
        int port = 22; // SSH port

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

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
                    .send(new Response.Listener.Adapter()
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

    @Test
    public void testExternalSiteRedirect() throws Exception
    {
        String host = "twitter.com";
        int port = 443;

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        ContentResponse response = client.newRequest(host, port)
                .scheme(HttpScheme.HTTPS.asString())
                .path("/twitter")
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    protected void assumeCanConnectTo(String host, int port)
    {
        try
        {
            new Socket(host, port).close();
        }
        catch (Throwable x)
        {
            Assume.assumeNoException(x);
        }
    }
}
