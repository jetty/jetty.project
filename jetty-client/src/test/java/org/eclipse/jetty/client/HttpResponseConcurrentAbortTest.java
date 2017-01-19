//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpResponseConcurrentAbortTest extends AbstractHttpClientServerTest
{
    private final CountDownLatch callbackLatch = new CountDownLatch(1);
    private final CountDownLatch failureLatch = new CountDownLatch(1);
    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private final AtomicBoolean success = new AtomicBoolean();

    public HttpResponseConcurrentAbortTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testAbortOnBegin() throws Exception
    {
        start(new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseBegin(new Response.BeginListener()
                {
                    @Override
                    public void onBegin(Response response)
                    {
                        abort(response);
                    }
                })
                .send(new TestResponseListener());
        Assert.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(6, TimeUnit.SECONDS));
        Assert.assertTrue(success.get());
    }

    @Test
    public void testAbortOnHeader() throws Exception
    {
        start(new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseHeader(new Response.HeaderListener()
                {
                    @Override
                    public boolean onHeader(Response response, HttpField field)
                    {
                        abort(response);
                        return true;
                    }
                })
                .send(new TestResponseListener());
        Assert.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(success.get());
    }

    @Test
    public void testAbortOnHeaders() throws Exception
    {
        start(new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseHeaders(new Response.HeadersListener()
                {
                    @Override
                    public void onHeaders(Response response)
                    {
                        abort(response);
                    }
                })
                .send(new TestResponseListener());
        Assert.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(success.get());
    }

    @Test
    public void testAbortOnContent() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                OutputStream output = response.getOutputStream();
                output.write(1);
                output.flush();
            }
        });

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseContent(new Response.ContentListener()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content)
                    {
                        abort(response);
                    }
                })
                .send(new TestResponseListener());
        Assert.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(success.get());
    }

    private void abort(final Response response)
    {
        new Thread("abort")
        {
            @Override
            public void run()
            {
                response.abort(new Exception());
            }
        }.start();

        try
        {
            // The failure callback must be executed asynchronously.
            boolean latched = failureLatch.await(4, TimeUnit.SECONDS);
            success.set(latched);

            // The complete callback must not be executed
            // until we return from this callback.
            latched = completeLatch.await(1, TimeUnit.SECONDS);
            success.set(!latched);

            callbackLatch.countDown();
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private class TestResponseListener extends Response.Listener.Adapter
    {
        @Override
        public void onFailure(Response response, Throwable failure)
        {
            failureLatch.countDown();
        }

        @Override
        public void onComplete(Result result)
        {
            Assert.assertTrue(result.isFailed());
            completeLatch.countDown();
        }
    }
}
