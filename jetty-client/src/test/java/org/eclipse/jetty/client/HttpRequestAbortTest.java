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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HttpRequestAbortTest extends AbstractHttpClientServerTest
{
    public HttpRequestAbortTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testAbortOnQueued() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicBoolean begin = new AtomicBoolean();
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Empty()
                    {
                        @Override
                        public void onQueued(Request request)
                        {
                            request.abort();
                        }

                        @Override
                        public void onBegin(Request request)
                        {
                            begin.set(true);
                        }
                    })
                    .send().get(5, TimeUnit.SECONDS);
            fail();
        }
        catch (ExecutionException x)
        {
            HttpRequestException xx = (HttpRequestException)x.getCause();
            Request request = xx.getRequest();
            Assert.assertNotNull(request);
            Assert.assertFalse(begin.get());
        }
    }

    @Test
    public void testAbortOnBegin() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicBoolean headers = new AtomicBoolean();
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Empty()
                    {
                        @Override
                        public void onBegin(Request request)
                        {
                            request.abort();
                        }

                        @Override
                        public void onHeaders(Request request)
                        {
                            headers.set(true);
                        }
                    })
                    .send().get(5, TimeUnit.SECONDS);
            fail();
        }
        catch (ExecutionException x)
        {
            HttpRequestException xx = (HttpRequestException)x.getCause();
            Request request = xx.getRequest();
            Assert.assertNotNull(request);
            Assert.assertFalse(headers.get());
        }
    }

    @Test
    public void testAbortOnHeaders() throws Exception
    {
        start(new EmptyServerHandler());

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .listener(new Request.Listener.Empty()
                {
                    @Override
                    public void onHeaders(Request request)
                    {
                        // Too late to abort
                        request.abort();
                    }
                })
                .send().get(5, TimeUnit.SECONDS);
        assertEquals(200, response.status());
    }

    @Test
    public void testAbortOnHeadersWithContent() throws Exception
    {
        final AtomicReference<IOException> failure = new AtomicReference<>();
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    IO.copy(request.getInputStream(), response.getOutputStream());
                }
                catch (IOException x)
                {
                    failure.set(x);
                    throw x;
                }
            }
        });

        // Test can behave in 2 ways:
        // A) if the request is failed before the request arrived, then we get an ExecutionException
        // B) if the request is failed after the request arrived, then we get a 500
        try
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Empty()
                    {
                        @Override
                        public void onHeaders(Request request)
                        {
                            request.abort();
                        }
                    })
                    .content(new ByteBufferContentProvider(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                    {
                        @Override
                        public long length()
                        {
                            return -1;
                        }
                    })
                    .send().get(5, TimeUnit.SECONDS);
            Assert.assertNotNull(failure.get());
            assertEquals(500, response.status());
        }
        catch (ExecutionException x)
        {
            HttpRequestException xx = (HttpRequestException)x.getCause();
            Request request = xx.getRequest();
            Assert.assertNotNull(request);
        }
    }
}
