//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

public class HttpResponseAbortTest extends AbstractHttpClientServerTest
{
    public HttpResponseAbortTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testAbortOnBegin() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseBegin(new Response.BeginListener()
                {
                    @Override
                    public void onBegin(Response response)
                    {
                        response.abort(new Exception());
                    }
                })
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        latch.countDown();
                    }
                });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortOnHeader() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseHeader(new Response.HeaderListener()
                {
                    @Override
                    public boolean onHeader(Response response, HttpField field)
                    {
                        response.abort(new Exception());
                        return true;
                    }
                })
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        latch.countDown();
                    }
                });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortOnHeaders() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseHeaders(new Response.HeadersListener()
                {
                    @Override
                    public void onHeaders(Response response)
                    {
                        response.abort(new Exception());
                    }
                })
                .send(new Response.CompleteListener()
                {

                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        latch.countDown();
                    }
                });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortOnContent() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    OutputStream output = response.getOutputStream();
                    output.write(1);
                    output.flush();
                    output.write(2);
                    output.flush();
                }
                catch (IOException ignored)
                {
                    // The client may have already closed, and we'll get an exception here, but it's expected
                }
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseContent(new Response.ContentListener()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content)
                    {
                        response.abort(new Exception());
                    }
                })
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        latch.countDown();
                    }
                });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
