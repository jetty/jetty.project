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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelStateTest
{
    private ArrayByteBufferPool.Tracking bufferPool;
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setUp()
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(null, null, bufferPool);
        connector = new LocalConnector(server);
        server.addConnector(connector);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testCompletedFromOtherThreadDoesNotCloseOutputOfNextRequest() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler("/");
        CountDownLatch firstCompletedLatch = new CountDownLatch(1);

        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().equals("/first"))
                {
                    response.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
                }
                else if (request.getRequestURI().equals("/second"))
                {
                    // The next request has started on the recycled HttpChannel, unblock completed().
                    ((BlockableCompletedHttpChannel)baseRequest.getHttpChannel()).nextRequestStarted();
                    // Write once the completion of the first request is done.
                    try
                    {
                        assertTrue(firstCompletedLatch.await(5, TimeUnit.SECONDS));
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    response.getOutputStream().write("second".getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        server.setHandler(new Handler.Wrapper(contextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                if (request.getHttpURI().getPath().equals("/first"))
                {
                    // Change the HttpChannel implementation for this one that can be blocked in completed().
                    // The HttpChannel is cached, so it is reused by the second request.
                    BlockableCompletedHttpChannel blockableCompletedHttpChannel = new BlockableCompletedHttpChannel(contextHandler, request);
                    request.getComponents().getCache().setAttribute(HttpChannel.class.getName(), blockableCompletedHttpChannel);

                    response = new org.eclipse.jetty.server.Response.Wrapper(request, response)
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
                                blockableCompletedHttpChannel.blockInCompleted();
                                writeCallback.succeeded();

                                firstCompletedLatch.countDown();
                            }).start(), writeCallback::failed));
                            blockableCompletedHttpChannel.awaitUntilInCompleted(5, TimeUnit.SECONDS);
                        }
                    };
                }
                return super.handle(request, response, callback);
            }
        });
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
        ContextHandler contextHandler = new ContextHandler("/");
        AtomicReference<HttpChannel> httpChannelRef = new AtomicReference<>();
        AtomicReference<Callback> lastWriteCallbackRef = new AtomicReference<>();

        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                httpChannelRef.set(baseRequest.getHttpChannel());
                // An error status without body makes the error response be
                // written with a callback that directly calls completed().
                response.sendError(HttpStatus.NO_CONTENT_204);
            }
        });

        server.setHandler(new Handler.Wrapper(contextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response = new org.eclipse.jetty.server.Response.Wrapper(request, response)
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
                        httpChannelRef.get().abort(new IOException("test abort"));
                    }
                };
                return super.handle(request, response, callback);
            }
        });
        server.start();

        connector.getResponse("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpChannel httpChannel = httpChannelRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> httpChannel.getCoreRequest() == null);
        assertFalse(httpChannel.isRequestCompleted());

        // Late completion of the error response write, which calls completed() on the recycled channel.
        lastWriteCallbackRef.get().succeeded();

        assertFalse(httpChannel.isRequestCompleted());
    }

    @Test
    public void testAbortedAsyncRequestNotifiesOnComplete() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler("/");
        AtomicReference<HttpChannel> httpChannelRef = new AtomicReference<>();
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        AtomicInteger onCompleteCount = new AtomicInteger();
        CountDownLatch asyncStartedLatch = new CountDownLatch(1);

        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                httpChannelRef.set(baseRequest.getHttpChannel());
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
        });
        server.setHandler(contextHandler);
        server.start();

        connector.executeRequest("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);
        assertTrue(asyncStartedLatch.await(5, TimeUnit.SECONDS));

        // Abort the async request, so completed() is never called.
        HttpChannel httpChannel = httpChannelRef.get();
        httpChannel.abort(new IOException("test abort"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> httpChannel.getCoreRequest() == null);
        assertThat(onCompleteCount.get(), is(1));
        assertThrows(IllegalStateException.class, () -> asyncContextRef.get().complete());
    }

    @Test
    public void testAbortedChannelIsNotReused() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler("/");
        Map<String, HttpChannel> httpChannels = new ConcurrentHashMap<>();

        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                HttpChannel httpChannel = baseRequest.getHttpChannel();
                httpChannels.put(request.getRequestURI(), httpChannel);
                if (request.getRequestURI().equals("/abort"))
                    httpChannel.abort(new IOException("test abort"));
            }
        });

        server.setHandler(new Handler.Wrapper(contextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Succeed the callback of the aborted request, so that the connection
                // is not closed and the next requests can reuse the cached HttpChannel.
                return super.handle(request, response, Callback.from(callback::succeeded, x -> callback.succeeded()));
            }
        });
        server.start();

        LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET /abort HTTP/1.1\r
            Host: localhost\r
            \r
            GET /next HTTP/1.1\r
            Host: localhost\r
            \r
            GET /last HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);
        for (int i = 0; i < 3; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }

        assertThat(httpChannels.size(), is(3));
        assertTrue(httpChannels.get("/abort").isAborted());
        // The aborted HttpChannel is replaced by a new one.
        assertThat(httpChannels.get("/next"), not(sameInstance(httpChannels.get("/abort"))));
        // A non-aborted HttpChannel is reused.
        assertThat(httpChannels.get("/last"), sameInstance(httpChannels.get("/next")));
    }

    @Test
    public void testOutputBufferReleasedWhenChannelNotRecycled() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // The error page is written directly from the HttpOutput aggregate buffer.
                response.sendError(HttpStatus.NOT_FOUND_404);
            }
        });
        server.setHandler(contextHandler);
        server.start();

        // The HttpChannel of a WebSocket request is never recycled, and the
        // connection is closed, so the HttpChannel is not reused either.
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Sec-WebSocket-Version: 13\r
            Connection: close\r
            \r
            """));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.getContent(), not(emptyString()));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0)));
    }

    // An HttpChannel that blocks the thread calling HttpChannelState.completed()
    // at the point where it accesses the Response, which is the window of the race.
    private static class BlockableCompletedHttpChannel extends HttpChannel
    {
        private final AtomicBoolean blockInCompleted = new AtomicBoolean();
        private final CountDownLatch inCompletedLatch = new CountDownLatch(1);
        private final CountDownLatch handledOrNextRequestLatch = new CountDownLatch(1);

        private BlockableCompletedHttpChannel(ContextHandler contextHandler, org.eclipse.jetty.server.Request request)
        {
            super(contextHandler, request.getConnectionMetaData());
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

        void nextRequestStarted()
        {
            handledOrNextRequestLatch.countDown();
        }

        @Override
        public Response getResponse()
        {
            if (blockInCompleted.compareAndSet(true, false))
            {
                inCompletedLatch.countDown();
                // If the HttpOutput is completed with the lock held, the handling thread
                // cannot see COMPLETED before it is done, so there is no window to widen,
                // and blocking here would deadlock with the handling thread.
                if (getState().isLockHeldByCurrentThread())
                    return super.getResponse();
                // Wait for the handling thread to either start the next
                // request on this channel (the bug) or to stop handling (the fix).
                // The next request must have started, as the HttpChannel
                // is recycled again when the next request starts.
                try
                {
                    assertTrue(handledOrNextRequestLatch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return super.getResponse();
        }

        @Override
        public boolean handle()
        {
            boolean result = super.handle();
            // If the request is completed, wait for the next request instead.
            if (!isRequestCompleted())
                handledOrNextRequestLatch.countDown();
            return result;
        }
    }
}
