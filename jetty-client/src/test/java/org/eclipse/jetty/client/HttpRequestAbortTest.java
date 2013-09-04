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
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
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

        final Throwable cause = new Exception();
        final AtomicBoolean begin = new AtomicBoolean();
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Adapter()
                    {
                        @Override
                        public void onQueued(Request request)
                        {
                            request.abort(cause);
                        }

                        @Override
                        public void onBegin(Request request)
                        {
                            begin.set(true);
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
            Assert.assertFalse(begin.get());
        }
    }

    @Slow
    @Test
    public void testAbortOnBegin() throws Exception
    {
        start(new EmptyServerHandler());

        final Throwable cause = new Exception();
        final CountDownLatch aborted = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Adapter()
                    {
                        @Override
                        public void onBegin(Request request)
                        {
                            if (request.abort(cause))
                                aborted.countDown();
                        }

                        @Override
                        public void onCommit(Request request)
                        {
                            committed.countDown();
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
            Assert.assertTrue(aborted.await(5, TimeUnit.SECONDS));
            Assert.assertFalse(committed.await(1, TimeUnit.SECONDS));
        }
    }

    @Slow
    @Test
    public void testAbortOnHeaders() throws Exception
    {
        start(new EmptyServerHandler());

        final Throwable cause = new Exception();
        final CountDownLatch aborted = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .listener(new Request.Listener.Adapter()
                    {
                        @Override
                        public void onHeaders(Request request)
                        {
                            if (request.abort(cause))
                                aborted.countDown();
                        }

                        @Override
                        public void onCommit(Request request)
                        {
                            committed.countDown();
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
            Assert.assertTrue(aborted.await(5, TimeUnit.SECONDS));
            Assert.assertFalse(committed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAbortOnCommit() throws Exception
    {
        start(new EmptyServerHandler());

        // Test can behave in 2 ways:
        // A) the request is failed before the response arrived, then we get an ExecutionException
        // B) the request is failed after the response arrived, we get the 200 OK

        final Throwable cause = new Exception();
        final CountDownLatch aborted = new CountDownLatch(1);
        try
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .onRequestCommit(new Request.CommitListener()
                    {
                        @Override
                        public void onCommit(Request request)
                        {
                            if (request.abort(cause))
                                aborted.countDown();
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(200, response.getStatus());
            Assert.assertFalse(aborted.await(1, TimeUnit.SECONDS));
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
            Assert.assertTrue(aborted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAbortOnCommitWithContent() throws Exception
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

        final Throwable cause = new Exception();
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .onRequestCommit(new Request.CommitListener()
                    {
                        @Override
                        public void onCommit(Request request)
                        {
                            request.abort(cause);
                        }
                    })
                    .content(new ByteBufferContentProvider(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                    {
                        @Override
                        public long getLength()
                        {
                            return -1;
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
        }
    }

    @Test
    public void testAbortOnContent() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                super.handle(target, baseRequest, request, response);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final Throwable cause = new Exception();
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .onRequestContent(new Request.ContentListener()
                    {
                        @Override
                        public void onContent(Request request, ByteBuffer content)
                        {
                            request.abort(cause);
                        }
                    })
                    .content(new ByteBufferContentProvider(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                    {
                        @Override
                        public long getLength()
                        {
                            return -1;
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
        }
    }

    @Slow
    @Test(expected = InterruptedException.class)
    public void testInterrupt() throws Exception
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
                .timeout(3 * delay, TimeUnit.MILLISECONDS)
                .scheme(scheme);

        final Thread thread = Thread.currentThread();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(delay);
                    thread.interrupt();
                }
                catch (InterruptedException x)
                {
                    throw new RuntimeException(x);
                }
            }
        }.start();

        request.send();
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

        final Request request = client.newRequest("localhost", connector.getLocalPort())
                .timeout(3 * delay, TimeUnit.MILLISECONDS)
                .scheme(scheme);

        final Throwable cause = new Exception();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(delay);
                    request.abort(cause);
                }
                catch (InterruptedException x)
                {
                    throw new RuntimeException(x);
                }
            }
        }.start();

        try
        {
            request.send();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
        }
    }

    @Slow
    @Test
    public void testAbortLongPollAsync() throws Exception
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

        final Throwable cause = new Exception();
        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(3 * delay, TimeUnit.MILLISECONDS);
        request.send(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                Assert.assertTrue(result.isFailed());
                Assert.assertSame(cause, result.getFailure());
                latch.countDown();
            }
        });

        TimeUnit.MILLISECONDS.sleep(delay);

        request.abort(cause);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

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

        final Throwable cause = new Exception();
        client.getProtocolHandlers().clear();
        client.getProtocolHandlers().add(new RedirectProtocolHandler(client)
        {
            @Override
            public void onComplete(Result result)
            {
                // Abort the request after the 3xx response but before issuing the next request
                if (!result.isFailed())
                    result.getRequest().abort(cause);
                super.onComplete(result);
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path("/redirect")
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertSame(cause, x.getCause());
        }
    }
}
