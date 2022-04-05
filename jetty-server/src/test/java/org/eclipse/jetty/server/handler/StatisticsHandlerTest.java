//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatisticsHandlerTest
{
    private Server _server;
    private ConnectionStatistics _statistics;
    private LocalConnector _connector;
    private LatchHandler _latchHandler;
    private StatisticsHandler _statsHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _statistics = new ConnectionStatistics();
        _connector.addBean(_statistics);
        _server.addConnector(_connector);

        _latchHandler = new LatchHandler();
        _statsHandler = new StatisticsHandler();

        _server.setHandler(_latchHandler);
        _latchHandler.setHandler(_statsHandler);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testRequest() throws Exception
    {
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2)};

        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();
                    barrier[1].await();
                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(x);
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();

        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(2, _statistics.getConnections());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

    @Test
    public void testTwoRequests() throws Exception
    {
        final CyclicBarrier[] barrier = {new CyclicBarrier(3), new CyclicBarrier(3)};
        _latchHandler.reset(2);
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();
                    barrier[1].await();
                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(x);
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

        _connector.executeRequest(request);
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(2, _statistics.getConnections());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(2, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(2, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());

        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

    @Test
    public void testSuspendResume() throws Exception
    {
        final long dispatchTime = 10;
        final long requestTime = 50;
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();

                    Thread.sleep(dispatchTime);

                    if (asyncHolder.get() == null)
                        asyncHolder.set(request.startAsync());
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();

        Thread.sleep(requestTime);

        asyncHolder.get().addListener(new AsyncListener()
        {
            @Override
            public void onTimeout(AsyncEvent event)
            {
            }

            @Override
            public void onStartAsync(AsyncEvent event)
            {
            }

            @Override
            public void onError(AsyncEvent event)
            {
            }

            @Override
            public void onComplete(AsyncEvent event)
            {
                try
                {
                    barrier[2].await();
                }
                catch (Exception ignored)
                {
                }
            }
        });
        asyncHolder.get().dispatch();

        barrier[0].await(); // entered app handler

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await(); // exiting app handler
        assertTrue(_latchHandler.await()); // exited stats handler
        barrier[2].await(); // onComplete called

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(1, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        assertThat(_statsHandler.getRequestTimeTotal(), greaterThanOrEqualTo(requestTime * 3 / 4));
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 0.01);

        assertThat(_statsHandler.getDispatchedTimeTotal(), greaterThanOrEqualTo(dispatchTime * 2 * 3 / 4));
        assertTrue(_statsHandler.getDispatchedTimeMean() + dispatchTime <= _statsHandler.getDispatchedTimeTotal());
        assertTrue(_statsHandler.getDispatchedTimeMax() + dispatchTime <= _statsHandler.getDispatchedTimeTotal());
    }

    @Test
    public void asyncDispatchTest() throws Exception
    {
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);
                try
                {
                    if (asyncHolder.get() == null)
                    {
                        barrier[0].await();
                        barrier[1].await();
                        AsyncContext asyncContext = request.startAsync();
                        asyncHolder.set(asyncContext);
                        asyncContext.dispatch();
                    }
                    else
                    {
                        barrier[2].await();
                        barrier[3].await();
                    }
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        // Before we have started async we have one active request.
        barrier[0].await();
        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        barrier[1].await();

        // After we are async the same request should still be active even though we have async dispatched.
        barrier[2].await();
        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        barrier[3].await();
    }

    @Test
    public void testThrownResponse() throws Exception
    {
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
            {
                try
                {
                    throw new IllegalStateException("expected");
                }
                catch (IllegalStateException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new IOException(e);
                }
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            String response = _connector.getResponse(request);
            assertThat(response, containsString("HTTP/1.1 500 Server Error"));
        }

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(0, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(0, _statsHandler.getResponses5xx());
        assertEquals(1, _statsHandler.getResponsesThrown());
    }

    @Test
    public void waitForSuspendedRequestTest() throws Exception
    {
        CyclicBarrier barrier = new CyclicBarrier(3);
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CountDownLatch dispatched = new CountDownLatch(1);
        _statsHandler.setGracefulShutdownWaitsForRequests(true);
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);

                try
                {
                    if (path.contains("async"))
                    {
                        asyncHolder.set(request.startAsync());
                        barrier.await();
                    }
                    else
                    {
                        barrier.await();
                        dispatched.await();
                    }
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        });
        _server.start();

        // One request to block while dispatched other will go async.
        _connector.executeRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        _connector.executeRequest("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n");

        // Ensure the requests have been dispatched and async started.
        barrier.await();
        AsyncContext asyncContext = Objects.requireNonNull(asyncHolder.get());

        // Shutdown should timeout as there are two active requests.
        Future<Void> shutdown = _statsHandler.shutdown();
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // When the dispatched thread exits we should still be waiting on the async request.
        dispatched.countDown();
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // Shutdown should complete only now the AsyncContext is completed.
        asyncContext.complete();
        shutdown.get(5, TimeUnit.MILLISECONDS);
    }

    @Test
    public void doNotWaitForSuspendedRequestTest() throws Exception
    {
        CyclicBarrier barrier = new CyclicBarrier(3);
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CountDownLatch dispatched = new CountDownLatch(1);
        _statsHandler.setGracefulShutdownWaitsForRequests(false);
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);

                try
                {
                    if (path.contains("async"))
                    {
                        asyncHolder.set(request.startAsync());
                        barrier.await();
                    }
                    else
                    {
                        barrier.await();
                        dispatched.await();
                    }
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        });
        _server.start();

        // One request to block while dispatched other will go async.
        _connector.executeRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        _connector.executeRequest("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n");

        // Ensure the requests have been dispatched and async started.
        barrier.await();
        assertNotNull(asyncHolder.get());

        // Shutdown should timeout as there is a request dispatched.
        Future<Void> shutdown = _statsHandler.shutdown();
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // When the dispatched thread exits we should shutdown even though we have a waiting async request.
        dispatched.countDown();
        shutdown.get(5, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testSuspendExpire() throws Exception
    {
        final long dispatchTime = 10;
        final long timeout = 100;
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();

                    Thread.sleep(dispatchTime);

                    if (asyncHolder.get() == null)
                    {
                        AsyncContext async = request.startAsync();
                        asyncHolder.set(async);
                        async.setTimeout(timeout);
                    }
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());
        asyncHolder.get().addListener(new AsyncListener()
        {
            @Override
            public void onTimeout(AsyncEvent event)
            {
                event.getAsyncContext().complete();
            }

            @Override
            public void onStartAsync(AsyncEvent event)
            {
            }

            @Override
            public void onError(AsyncEvent event)
            {
            }

            @Override
            public void onComplete(AsyncEvent event)
            {
                try
                {
                    barrier[2].await();
                }
                catch (Exception ignored)
                {
                }
            }
        });

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        barrier[2].await();

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(1, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        assertTrue(_statsHandler.getRequestTimeTotal() >= (timeout + dispatchTime) * 3 / 4);
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 0.01);

        assertThat(_statsHandler.getDispatchedTimeTotal(), greaterThanOrEqualTo(dispatchTime * 3 / 4));
    }

    @Test
    public void testSuspendComplete() throws Exception
    {
        final long dispatchTime = 10;
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2)};
        final CountDownLatch latch = new CountDownLatch(1);

        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();

                    Thread.sleep(dispatchTime);

                    if (asyncHolder.get() == null)
                    {
                        AsyncContext async = request.startAsync();
                        asyncHolder.set(async);
                    }
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        asyncHolder.get().addListener(new AsyncListener()
        {
            @Override
            public void onTimeout(AsyncEvent event)
            {
            }

            @Override
            public void onStartAsync(AsyncEvent event)
            {
            }

            @Override
            public void onError(AsyncEvent event)
            {
            }

            @Override
            public void onComplete(AsyncEvent event)
            {
                try
                {
                    latch.countDown();
                }
                catch (Exception ignored)
                {
                }
            }
        });
        long requestTime = 20;
        Thread.sleep(requestTime);
        asyncHolder.get().complete();
        latch.await();

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        assertTrue(_statsHandler.getRequestTimeTotal() >= (dispatchTime + requestTime) * 3 / 4);
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 0.01);

        assertTrue(_statsHandler.getDispatchedTimeTotal() >= dispatchTime * 3 / 4);
        assertTrue(_statsHandler.getDispatchedTimeTotal() < _statsHandler.getRequestTimeTotal());
        assertEquals(_statsHandler.getDispatchedTimeTotal(), _statsHandler.getDispatchedTimeMax());
        assertEquals(_statsHandler.getDispatchedTimeTotal(), _statsHandler.getDispatchedTimeMean(), 0.01);
    }

    @Test
    public void testAsyncRequestWithShutdown() throws Exception
    {
        long delay = 500;
        CountDownLatch serverLatch = new CountDownLatch(1);
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(delay);
                        asyncContext.complete();
                    }
                    catch (InterruptedException e)
                    {
                        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                }).start();
                serverLatch.countDown();
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        Future<Void> shutdown = _statsHandler.shutdown();
        assertFalse(shutdown.isDone());

        Thread.sleep(delay / 2);
        assertFalse(shutdown.isDone());

        Thread.sleep(delay);
        assertTrue(shutdown.isDone());
    }

    /**
     * This handler is external to the statistics handler and it is used to ensure that statistics handler's
     * handle() is fully executed before asserting its values in the tests, to avoid race conditions with the
     * tests' code where the test executes but the statistics handler has not finished yet.
     */
    private static class LatchHandler extends HandlerWrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            final CountDownLatch latch = _latch;
            try
            {
                super.handle(path, request, httpRequest, httpResponse);
            }
            finally
            {
                latch.countDown();
            }
        }

        private void reset()
        {
            _latch = new CountDownLatch(1);
        }

        private void reset(int count)
        {
            _latch = new CountDownLatch(count);
        }

        private boolean await() throws InterruptedException
        {
            return _latch.await(10000, TimeUnit.MILLISECONDS);
        }
    }
}
