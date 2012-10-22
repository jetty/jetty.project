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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

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
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getCause(), Matchers.instanceOf(HttpResponseException.class));
            Assert.assertFalse(begin.get());
        }
    }

    @Slow
    @Test
    public void testAbortOnBegin() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch aborted = new CountDownLatch(1);
        final CountDownLatch headers = new CountDownLatch(1);
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Empty()
                    {
                        @Override
                        public void onBegin(Request request)
                        {
                            if (request.abort())
                                aborted.countDown();
                        }

                        @Override
                        public void onHeaders(Request request)
                        {
                            headers.countDown();
                        }
                    })
                    .send().get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getCause(), Matchers.instanceOf(HttpResponseException.class));
            Assert.assertTrue(aborted.await(5, TimeUnit.SECONDS));
            Assert.assertFalse(headers.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAbortOnHeaders() throws Exception
    {
        start(new EmptyServerHandler());

        // Test can behave in 2 ways:
        // A) the request is failed before the response arrived, then we get an ExecutionException
        // B) the request is failed after the response arrived, we get the 200 OK

        final CountDownLatch aborted = new CountDownLatch(1);
        try
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Empty()
                    {
                        @Override
                        public void onHeaders(Request request)
                        {
                            if (request.abort())
                                aborted.countDown();
                        }
                    })
                    .send().get(5, TimeUnit.SECONDS);
            Assert.assertEquals(200, response.status());
            Assert.assertFalse(aborted.await(1, TimeUnit.SECONDS));
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            Assert.assertTrue(cause instanceof HttpResponseException);
            Assert.assertTrue(aborted.await(5, TimeUnit.SECONDS));
        }
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

        // Test can behave in 3 ways:
        // A) non-SSL, if the request is failed before the response arrived, then we get an ExecutionException
        // B) non-SSL, if the request is failed after the response arrived, then we get the 500
        // C) SSL, the server tries to write the 500, but the connection is already closed, the client
        //    reads -1 with a pending exchange and fails the response with an EOFException
        StdErrLog.getLogger(HttpChannel.class).setHideStacks(true);
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
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
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof EOFException)
            {
                // Server closed abruptly, behavior C
            }
            else if (cause instanceof HttpRequestException)
            {
                // Request failed, behavior B
                HttpRequestException xx = (HttpRequestException)cause;
                Request request = xx.getRequest();
                Assert.assertNotNull(request);
            }
            else if (cause instanceof HttpResponseException)
            {
                // Response failed, behavior A
                HttpResponseException xx = (HttpResponseException)cause;
                Response response = xx.getResponse();
                Assert.assertNotNull(response);
            }
            else
            {
                throw x;
            }
        }
        finally
        {
            StdErrLog.getLogger(HttpChannel.class).setHideStacks(false);
        }
    }

    @Slow
    @Test
    public void testAbortLongPoll() throws Exception
    {
        final long delay = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    TimeUnit.MILLISECONDS.sleep(2 * delay);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme);
        Future<ContentResponse> future = request.send();

        TimeUnit.MILLISECONDS.sleep(delay);

        request.abort();

        try
        {
            future.get(5, TimeUnit.SECONDS);
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            Assert.assertTrue(cause instanceof HttpResponseException);
        }
    }

    // TODO: this test is not passing yet
    @Ignore()
    @Test
    public void testAbortConversation() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (!"/done".equals(request.getRequestURI()))
                    response.sendRedirect("/done");
            }
        });

        client.getProtocolHandlers().clear();
        client.getProtocolHandlers().add(new RedirectProtocolHandler(client)
        {
            @Override
            public void onComplete(Result result)
            {
                // Abort the request after the 3xx response but before issuing the next request
                if (!result.isFailed())
                    result.getRequest().abort();
                super.onComplete(result);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/redirect")
                .send()
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(1000);
    }
}
