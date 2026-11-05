//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletChannelStateTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setUp()
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testCompletedFromOtherThreadDoesNotCloseOutputOfNextRequest() throws Exception
    {
        var servletContextHandler = new ServletContextHandler("/")
        {
            private final CountDownLatch firstCompletedLatch = new CountDownLatch(1);

            void awaitUntilFirstCompleted(long timeout, TimeUnit unit)
            {
                try
                {
                    assertTrue(firstCompletedLatch.await(timeout, unit));
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                if (request.getHttpURI().getPath().equals("/first"))
                {
                    // Change the ServletChannel implementation for this one that can be blocked in completed().
                    // The ServletChannel is cached, so it is reused by the second request.
                    BlockableCompletedServletChannel blockableCompletedServletChannel = new BlockableCompletedServletChannel(this, request);
                    request.getComponents().getCache().setAttribute(ServletChannel.class.getName(), blockableCompletedServletChannel);

                    response = new Response.Wrapper(request, response)
                    {
                        @Override
                        public void write(boolean last, ByteBuffer byteBuffer, Callback writeCallback)
                        {
                            if (!last)
                            {
                                super.write(false, byteBuffer, writeCallback);
                                return;
                            }

                            // Complete the last write of the first request from another thread,
                            // while the handling thread is still handling the request.
                            super.write(true, byteBuffer, Callback.from(() -> new Thread(() ->
                            {
                                // Succeeding the callback will call completed(), make it block in it.
                                blockableCompletedServletChannel.blockInCompleted();
                                writeCallback.succeeded();

                                firstCompletedLatch.countDown();
                            }).start(), writeCallback::failed));
                            blockableCompletedServletChannel.awaitUntilInCompleted(5, TimeUnit.SECONDS);
                        }
                    };
                }
                return super.handle(request, response, callback);
            }
        };
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().equals("/first"))
                {
                    response.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
                }
                else if (request.getRequestURI().equals("/second"))
                {
                    // Write once the completion of the first request is done.
                    servletContextHandler.awaitUntilFirstCompleted(5, TimeUnit.SECONDS);
                    response.getOutputStream().write("second".getBytes(StandardCharsets.UTF_8));
                }
            }
        }, "/*");
        server.setHandler(servletContextHandler);
        server.start();

        LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET /first HTTP/1.1\r
            Host: localhost\r
            \r
            GET /second HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response first = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(first.getStatus(), is(HttpStatus.OK_200));
        assertThat(first.getContent(), is("first"));
        HttpTester.Response second = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(second.getStatus(), is(HttpStatus.OK_200));
        assertThat(second.getContent(), is("second"));
    }

    @Test
    public void testCompletedAfterAbortAndRecycleIsNoop() throws Exception
    {
        AtomicReference<ServletChannel> servletChannelRef = new AtomicReference<>();
        AtomicReference<Callback> lastWriteCallbackRef = new AtomicReference<>();
        var servletContextHandler = new ServletContextHandler("/")
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response = new Response.Wrapper(request, response)
                {
                    @Override
                    public void write(boolean last, ByteBuffer byteBuffer, Callback writeCallback)
                    {
                        if (!last)
                        {
                            super.write(false, byteBuffer, writeCallback);
                            return;
                        }

                        // Abort while the last write is pending, and keep its
                        // callback to complete it after the channel got recycled.
                        lastWriteCallbackRef.set(writeCallback);
                        servletChannelRef.get().abort(new IOException("test abort"));
                    }
                };
                return super.handle(request, response, callback);
            }
        };
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                servletChannelRef.set(ServletContextRequest.getServletContextRequest(request).getServletChannel());
                // An error status without body makes the error response be
                // written with a callback that directly calls completed().
                response.sendError(HttpStatus.NO_CONTENT_204);
            }
        }, "/*");
        server.setHandler(servletContextHandler);
        server.start();

        connector.getResponse("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        ServletChannel servletChannel = servletChannelRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> servletChannel.getServletContextRequest() == null);
        assertFalse(servletChannel.isRequestCompleted());

        // Late completion of the error response write, which calls completed() on the recycled channel.
        lastWriteCallbackRef.get().succeeded();

        assertFalse(servletChannel.isRequestCompleted());
    }

    @Test
    public void testAbortedAsyncRequestNotifiesOnComplete() throws Exception
    {
        AtomicReference<ServletChannel> servletChannelRef = new AtomicReference<>();
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        AtomicInteger onCompleteCount = new AtomicInteger();
        CountDownLatch asyncStartedLatch = new CountDownLatch(1);
        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
            {
                servletChannelRef.set(ServletContextRequest.getServletContextRequest(request).getServletChannel());
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                        onCompleteCount.incrementAndGet();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event)
                    {
                    }
                });
                asyncContextRef.set(asyncContext);
                asyncStartedLatch.countDown();
            }
        }, "/*");
        server.setHandler(servletContextHandler);
        server.start();

        connector.executeRequest("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);
        assertTrue(asyncStartedLatch.await(5, TimeUnit.SECONDS));

        // Abort the async request, so completed() is never called.
        ServletChannel servletChannel = servletChannelRef.get();
        servletChannel.abort(new IOException("test abort"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> servletChannel.getServletContextRequest() == null);
        assertThat(onCompleteCount.get(), is(1));
        assertThrows(IllegalStateException.class, () -> asyncContextRef.get().complete());
    }

    // A ServletChannel that blocks the thread calling ServletChannelState.completed()
    // at the point where it accesses the HttpOutput, which is the window of the race.
    private static class BlockableCompletedServletChannel extends ServletChannel
    {
        private final AtomicBoolean blockInCompleted = new AtomicBoolean();
        private final CountDownLatch inCompletedLatch = new CountDownLatch(1);
        private final CountDownLatch handledOrRecycledLatch = new CountDownLatch(1);

        private BlockableCompletedServletChannel(ServletContextHandler context, Request request)
        {
            super(context, request);
        }

        void blockInCompleted()
        {
            blockInCompleted.set(true);
        }

        void awaitUntilInCompleted(long timeout, TimeUnit unit)
        {
            try
            {
                assertTrue(inCompletedLatch.await(timeout, unit));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HttpOutput getHttpOutput()
        {
            if (blockInCompleted.compareAndSet(true, false))
            {
                inCompletedLatch.countDown();
                // If the HttpOutput is completed with the lock held, the handling thread
                // cannot see COMPLETED before it is done, so there is no window to widen,
                // and blocking here would deadlock with the handling thread.
                if (getServletRequestState().isLockHeldByCurrentThread())
                    return super.getHttpOutput();
                try
                {
                    // Wait for the handling thread to either recycle the
                    // channel (the bug) or to stop handling (the fix).
                    assertTrue(handledOrRecycledLatch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return super.getHttpOutput();
        }

        @Override
        public void handle()
        {
            super.handle();
            // If the request is completed, wait for the recycle instead.
            if (!isRequestCompleted())
                handledOrRecycledLatch.countDown();
        }

        @Override
        void recycle(Throwable x)
        {
            super.recycle(x);
            handledOrRecycledLatch.countDown();
        }
    }
}
