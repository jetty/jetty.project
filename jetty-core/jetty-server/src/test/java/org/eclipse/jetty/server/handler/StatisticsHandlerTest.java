//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    public void testDataReadRate() throws Exception
    {
        AtomicLong readRate = new AtomicLong(-1L);

        _statsHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> process(request, response, callback));
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        Long rr = (Long)request.getAttribute("o.e.j.s.h.StatsHandler.dataReadRate");
                        readRate.set(rr);
                        //System.err.println("over; read rate=" + rr + " b/s");
                        callback.succeeded();
                        return;
                    }
                }
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Length: 1000\r\n" +
            "\r\n";

        LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request);

        // send 1 byte per ms -> should avg to ~1000 bytes/s
        for (int i = 0; i < 1000; i++)
        {
            Thread.sleep(1);
            endPoint.addInput(ByteBuffer.allocate(1));
        }

        _latchHandler.await();
        assertThat(readRate.get(), allOf(greaterThan(600L), lessThan(1100L)));
    }

    @Test
    public void testDataWriteRate() throws Exception
    {
        AtomicLong writeRate = new AtomicLong(-1L);
        CountDownLatch latch = new CountDownLatch(1);

        _statsHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                write(response, 0, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        Long wr = (Long)request.getAttribute("o.e.j.s.h.StatsHandler.dataWriteRate");
                        //System.err.println("over; write rate=" + wr + " b/s");
                        writeRate.set(wr);

                        callback.succeeded();
                        latch.countDown();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        callback.failed(x);
                        latch.countDown();
                    }
                });
            }

            private void write(Response response, int counter, Callback finalCallback)
            {
                try
                {
                    Thread.sleep(1);
                }
                catch (InterruptedException e)
                {
                    // ignore
                }

                if (counter < 1000)
                {
                    Callback cb = new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            write(response, counter + 1, finalCallback);
                        }

                        @Override
                        public void failed(Throwable x)
                        {
                            finalCallback.failed(x);
                        }
                    };
                    response.write(false, ByteBuffer.allocate(1), cb);
                }
                else
                {
                    response.write(true, ByteBuffer.allocate(1), finalCallback);
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

        _connector.executeRequest(request);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(writeRate.get(), allOf(greaterThan(600L), lessThan(1100L)));
    }

    @Test
    public void testMinimumDataReadRateHandler() throws Exception
    {
        StatisticsHandler.MinimumDataRateHandler mdrh = new StatisticsHandler.MinimumDataRateHandler(1100, 0);
        mdrh.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> process(request, response, callback));
                        return;
                    }

                    if (chunk instanceof Content.Chunk.Error errorContent)
                    {
                        callback.failed(errorContent.getCause());
                        return;
                    }

                    chunk.release();
                    if (chunk.isLast())
                    {
                        callback.succeeded();
                        return;
                    }
                }
            }
        });

        _latchHandler.setHandler(mdrh);
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 1000\r\n" +
                "\r\n";

        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request);

            // send 10 byte every 10 ms -> should avg to ~1000 bytes/s
            for (int i = 0; i < 100; i++)
            {
                Thread.sleep(10);
                endPoint.addInput(ByteBuffer.allocate(10));
            }

            _latchHandler.await();
            AtomicInteger statusHolder = new AtomicInteger();
            endPoint.waitForResponse(false, 5, TimeUnit.SECONDS, statusHolder::set);
            assertThat(statusHolder.get(), is(500));
        }
    }

    @Test
    public void testMinimumDataWriteRateHandler() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        StatisticsHandler.MinimumDataRateHandler mdrh = new StatisticsHandler.MinimumDataRateHandler(0, 1100);
        int expectedContentLength = 1000;
        mdrh.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                write(response, 0, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        callback.succeeded();
                        latch.countDown();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        callback.failed(x);
                        latch.countDown();
                    }
                });
            }

            private void write(Response response, int counter, Callback finalCallback)
            {
                try
                {
                    Thread.sleep(1);
                }
                catch (InterruptedException e)
                {
                    // ignore
                }

                if (counter < expectedContentLength)
                {
                    Callback cb = new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            write(response, counter + 1, finalCallback);
                        }

                        @Override
                        public void failed(Throwable x)
                        {
                            finalCallback.failed(x);
                        }
                    };
                    response.write(false, ByteBuffer.allocate(1), cb);
                }
                else
                {
                    response.write(true, ByteBuffer.allocate(1), finalCallback);
                }
            }
        });

        _latchHandler.setHandler(mdrh);
        _server.start();

        String request = """
                GET / HTTP/1.1
                Host: localhost
                
                """;

        LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        AtomicInteger statusHolder = new AtomicInteger();
        ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS, statusHolder::set);
        assertThat(statusHolder.get(), is(200));
        assertThat(byteBuffer.remaining(), lessThan(expectedContentLength));
    }

    @Test
    public void testTwoRequestsSerially() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new TripleBarrierHandlerProcessor(barrier));
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

        // 1st request
        LocalConnector.LocalEndPoint localEndPoint = _connector.executeRequest(request);

        barrier[0].await();
        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(1, _statsHandler.getProcessings());
        assertEquals(1, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(1, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
//        assertEquals(0, _statsHandler.getAsyncRequests());
//        assertEquals(0, _statsHandler.getAsyncDispatches());
//        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();
        barrier[2].reset();

        assertThat(localEndPoint.getResponse(), containsString(" 200 OK"));

        // 2nd request
        localEndPoint = _connector.executeRequest(request);

        barrier[0].await();
        assertEquals(2, _statistics.getConnections());
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(2, _statsHandler.getHandlings());
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(1, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(2, _statsHandler.getHandlings());
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
//        assertEquals(0, _statsHandler.getAsyncRequests());
//        assertEquals(0, _statsHandler.getAsyncDispatches());
//        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

    @Test
    public void testTwoRequestsInParallel() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(3), new CyclicBarrier(3), new CyclicBarrier(3)};
        _latchHandler.reset(2);
        _statsHandler.setHandler(new TripleBarrierHandlerProcessor(barrier));
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
        assertEquals(2, _statsHandler.getHandlings());
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(2, _statsHandler.getProcessingsActive());
        assertEquals(2, _statsHandler.getProcessingsMax());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());
        assertEquals(2, _statsHandler.getHandlings());
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(2, _statsHandler.getProcessingsMax());
//        assertEquals(0, _statsHandler.getAsyncRequests());
//        assertEquals(0, _statsHandler.getAsyncDispatches());
//        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

// TODO
//    @Test
//    public void testSuspendResume() throws Exception
//    {
//        final long dispatchTime = 10;
//        final long requestTime = 50;
//        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
//        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
//        _statsHandler.setHandler(new AbstractHandler()
//        {
//            @Override
//            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
//            {
//                request.setHandled(true);
//                try
//                {
//                    barrier[0].await();
//
//                    Thread.sleep(dispatchTime);
//
//                    if (asyncHolder.get() == null)
//                        asyncHolder.set(request.startAsync());
//                }
//                catch (Exception x)
//                {
//                    throw new ServletException(x);
//                }
//                finally
//                {
//                    try
//                    {
//                        barrier[1].await();
//                    }
//                    catch (Exception ignored)
//                    {
//                    }
//                }
//            }
//        });
//        _server.start();
//
//        String request = "GET / HTTP/1.1\r\n" +
//            "Host: localhost\r\n" +
//            "\r\n";
//        _connector.executeRequest(request);
//
//        barrier[0].await();
//
//        assertEquals(1, _statistics.getConnections());
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(1, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(1, _statsHandler.getDispatchedActive());
//
//        barrier[1].await();
//        assertTrue(_latchHandler.await());
//        assertNotNull(asyncHolder.get());
//
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(1, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(0, _statsHandler.getDispatchedActive());
//
//        _latchHandler.reset();
//        barrier[0].reset();
//        barrier[1].reset();
//
//        Thread.sleep(requestTime);
//
//        asyncHolder.get().addListener(new AsyncListener()
//        {
//            @Override
//            public void onTimeout(AsyncEvent event)
//            {
//            }
//
//            @Override
//            public void onStartAsync(AsyncEvent event)
//            {
//            }
//
//            @Override
//            public void onError(AsyncEvent event)
//            {
//            }
//
//            @Override
//            public void onComplete(AsyncEvent event)
//            {
//                try
//                {
//                    barrier[2].await();
//                }
//                catch (Exception ignored)
//                {
//                }
//            }
//        });
//        asyncHolder.get().dispatch();
//
//        barrier[0].await(); // entered app handler
//
//        assertEquals(1, _statistics.getConnections());
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(1, _statsHandler.getRequestsActive());
//        assertEquals(2, _statsHandler.getDispatched());
//        assertEquals(1, _statsHandler.getDispatchedActive());
//
//        barrier[1].await(); // exiting app handler
//        assertTrue(_latchHandler.await()); // exited stats handler
//        barrier[2].await(); // onComplete called
//
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(0, _statsHandler.getRequestsActive());
//        assertEquals(2, _statsHandler.getDispatched());
//        assertEquals(0, _statsHandler.getDispatchedActive());
//
//        assertEquals(1, _statsHandler.getAsyncRequests());
//        assertEquals(1, _statsHandler.getAsyncDispatches());
//        assertEquals(0, _statsHandler.getExpires());
//        assertEquals(1, _statsHandler.getResponses2xx());
//
//        assertThat(_statsHandler.getRequestTimeTotal(), greaterThanOrEqualTo(requestTime * 3 / 4));
//        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
//        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 0.01);
//
//        assertThat(_statsHandler.getDispatchedTimeTotal(), greaterThanOrEqualTo(dispatchTime * 2 * 3 / 4));
//        assertTrue(_statsHandler.getDispatchedTimeMean() + dispatchTime <= _statsHandler.getDispatchedTimeTotal());
//        assertTrue(_statsHandler.getDispatchedTimeMax() + dispatchTime <= _statsHandler.getDispatchedTimeTotal());
//    }
//
    @Test
    public void testHandlingsIncrementThenProcessingsIncrement() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                barrier[0].await();
                barrier[1].await();
                return (rq, rs, callback) ->
                {
                    barrier[2].await();
                    barrier[3].await();
                    callback.succeeded();
                    barrier[4].await();
                };
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(0, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(0, _statsHandler.getProcessingsMax());
        barrier[1].await();
        barrier[2].await();

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(1, _statsHandler.getProcessings());
        assertEquals(1, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
        barrier[3].await();
        barrier[4].await();

        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(1, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
    }

    @Test
    public void testThrownHandles() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public Request.Processor handle(Request request)
            {
                throw new IllegalStateException("expected");
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            String response = _connector.getResponse(request);
            assertThat(response, containsString("HTTP/1.1 500 Server Error"));
        }

        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(0, _statsHandler.getRequestsActiveMax());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(0, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(0, _statsHandler.getResponses5xx());
        assertEquals(1, _statsHandler.getHandlingErrors());
        assertEquals(0, _statsHandler.getProcessingErrors());
    }

    @Test
    public void testThrownProcesses() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public Request.Processor handle(Request request)
            {
                return (req, resp, cp) ->
                {
                    throw new IllegalStateException("expected");
                };
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            String response = _connector.getResponse(request);
            assertThat(response, containsString("HTTP/1.1 500 Server Error"));
        }

        assertEquals(1, _statsHandler.getHandlings());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(0, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(1, _statsHandler.getResponses5xx());
        assertEquals(0, _statsHandler.getHandlingErrors());
        assertEquals(1, _statsHandler.getProcessingErrors());
    }

//
//    @Test
//    public void waitForSuspendedRequestTest() throws Exception
//    {
//        CyclicBarrier barrier = new CyclicBarrier(3);
//        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
//        final CountDownLatch dispatched = new CountDownLatch(1);
//        _statsHandler.setGracefulShutdownWaitsForRequests(true);
//        _statsHandler.setHandler(new AbstractHandler()
//        {
//            @Override
//            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
//            {
//                request.setHandled(true);
//
//                try
//                {
//                    if (path.contains("async"))
//                    {
//                        asyncHolder.set(request.startAsync());
//                        barrier.await();
//                    }
//                    else
//                    {
//                        barrier.await();
//                        dispatched.await();
//                    }
//                }
//                catch (Exception e)
//                {
//                    throw new ServletException(e);
//                }
//            }
//        });
//        _server.start();
//
//        // One request to block while dispatched other will go async.
//        _connector.executeRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
//        _connector.executeRequest("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n");
//
//        // Ensure the requests have been dispatched and async started.
//        barrier.await();
//        AsyncContext asyncContext = Objects.requireNonNull(asyncHolder.get());
//
//        // Shutdown should timeout as there are two active requests.
//        Future<Void> shutdown = _statsHandler.shutdown();
//        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));
//
//        // When the dispatched thread exits we should still be waiting on the async request.
//        dispatched.countDown();
//        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));
//
//        // Shutdown should complete only now the AsyncContext is completed.
//        asyncContext.complete();
//        shutdown.get(5, TimeUnit.MILLISECONDS);
//    }
//
//    @Test
//    public void doNotWaitForSuspendedRequestTest() throws Exception
//    {
//        CyclicBarrier barrier = new CyclicBarrier(3);
//        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
//        final CountDownLatch dispatched = new CountDownLatch(1);
//        _statsHandler.setGracefulShutdownWaitsForRequests(false);
//        _statsHandler.setHandler(new AbstractHandler()
//        {
//            @Override
//            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
//            {
//                request.setHandled(true);
//
//                try
//                {
//                    if (path.contains("async"))
//                    {
//                        asyncHolder.set(request.startAsync());
//                        barrier.await();
//                    }
//                    else
//                    {
//                        barrier.await();
//                        dispatched.await();
//                    }
//                }
//                catch (Exception e)
//                {
//                    throw new ServletException(e);
//                }
//            }
//        });
//        _server.start();
//
//        // One request to block while dispatched other will go async.
//        _connector.executeRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
//        _connector.executeRequest("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n");
//
//        // Ensure the requests have been dispatched and async started.
//        barrier.await();
//        assertNotNull(asyncHolder.get());
//
//        // Shutdown should timeout as there is a request dispatched.
//        Future<Void> shutdown = _statsHandler.shutdown();
//        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));
//
//        // When the dispatched thread exits we should shutdown even though we have a waiting async request.
//        dispatched.countDown();
//        shutdown.get(5, TimeUnit.MILLISECONDS);
//    }
//
//    @Test
//    public void testSuspendExpire() throws Exception
//    {
//        final long dispatchTime = 10;
//        final long timeout = 100;
//        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
//        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
//        _statsHandler.setHandler(new AbstractHandler()
//        {
//            @Override
//            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException
//            {
//                request.setHandled(true);
//                try
//                {
//                    barrier[0].await();
//
//                    Thread.sleep(dispatchTime);
//
//                    if (asyncHolder.get() == null)
//                    {
//                        AsyncContext async = request.startAsync();
//                        asyncHolder.set(async);
//                        async.setTimeout(timeout);
//                    }
//                }
//                catch (Exception x)
//                {
//                    throw new ServletException(x);
//                }
//                finally
//                {
//                    try
//                    {
//                        barrier[1].await();
//                    }
//                    catch (Exception ignored)
//                    {
//                    }
//                }
//            }
//        });
//        _server.start();
//
//        String request = "GET / HTTP/1.1\r\n" +
//            "Host: localhost\r\n" +
//            "\r\n";
//        _connector.executeRequest(request);
//
//        barrier[0].await();
//
//        assertEquals(1, _statistics.getConnections());
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(1, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(1, _statsHandler.getDispatchedActive());
//
//        barrier[1].await();
//        assertTrue(_latchHandler.await());
//        assertNotNull(asyncHolder.get());
//        asyncHolder.get().addListener(new AsyncListener()
//        {
//            @Override
//            public void onTimeout(AsyncEvent event)
//            {
//                event.getAsyncContext().complete();
//            }
//
//            @Override
//            public void onStartAsync(AsyncEvent event)
//            {
//            }
//
//            @Override
//            public void onError(AsyncEvent event)
//            {
//            }
//
//            @Override
//            public void onComplete(AsyncEvent event)
//            {
//                try
//                {
//                    barrier[2].await();
//                }
//                catch (Exception ignored)
//                {
//                }
//            }
//        });
//
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(1, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(0, _statsHandler.getDispatchedActive());
//
//        barrier[2].await();
//
//        assertEquals(1, _statsHandler.getRequests());
//        assertEquals(0, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(0, _statsHandler.getDispatchedActive());
//
//        assertEquals(1, _statsHandler.getAsyncRequests());
//        assertEquals(0, _statsHandler.getAsyncDispatches());
//        assertEquals(1, _statsHandler.getExpires());
//        assertEquals(1, _statsHandler.getResponses2xx());
//
//        assertTrue(_statsHandler.getRequestTimeTotal() >= (timeout + dispatchTime) * 3 / 4);
//        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
//        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 0.01);
//
//        assertThat(_statsHandler.getDispatchedTimeTotal(), greaterThanOrEqualTo(dispatchTime * 3 / 4));
//    }

    @Test
    public void testHandlingProcessingTime() throws Exception
    {
        final long handleTime = 10;
        final long processTime = 35;
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};

        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                barrier[0].await();
                Thread.sleep(handleTime);
                return (rq, rs, callback) ->
                {
                    try
                    {
                        barrier[1].await();
                        Thread.sleep(processTime);
                        callback.succeeded();
                    }
                    finally
                    {
                        try
                        {
                            barrier[2].await();
                        }
                        catch (Throwable x)
                        {
                            callback.failed(x);
                        }
                    }
                };
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnections());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
//        assertEquals(1, _statsHandler.getDispatched());
//        assertEquals(0, _statsHandler.getDispatchedActive());
//        assertEquals(1, _statsHandler.getAsyncRequests());
//        assertEquals(0, _statsHandler.getAsyncDispatches());
//        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        assertThat(_statsHandler.getRequestTimeTotal(), allOf(greaterThan(TimeUnit.MILLISECONDS.toNanos(processTime + handleTime) * 3 / 4), lessThan(TimeUnit.MILLISECONDS.toNanos(processTime + handleTime) * 5)));
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 1.0);

        assertThat(_statsHandler.getHandlingTimeTotal(), allOf(greaterThan(TimeUnit.MILLISECONDS.toNanos(handleTime) * 3 / 4), lessThan(TimeUnit.MILLISECONDS.toNanos(handleTime) * 5)));
        assertTrue(_statsHandler.getHandlingTimeTotal() < _statsHandler.getRequestTimeTotal());
        assertEquals(_statsHandler.getHandlingTimeTotal(), _statsHandler.getHandlingTimeMax());
        assertEquals(_statsHandler.getHandlingTimeTotal(), _statsHandler.getHandlingTimeMean(), 1.0);

        assertThat(_statsHandler.getProcessingTimeTotal(), allOf(greaterThan(TimeUnit.MILLISECONDS.toNanos(processTime) * 3 / 4), lessThan(TimeUnit.MILLISECONDS.toNanos(processTime) * 5)));
        assertTrue(_statsHandler.getProcessingTimeTotal() < _statsHandler.getRequestTimeTotal());
        assertEquals(_statsHandler.getProcessingTimeTotal(), _statsHandler.getProcessingTimeMax());
        assertEquals(_statsHandler.getProcessingTimeTotal(), _statsHandler.getProcessingTimeMean(), 1.0);

        assertThat(_statsHandler.getRequestTimeTotal(), greaterThan(_statsHandler.getHandlingTimeTotal() + _statsHandler.getProcessingTimeTotal()));
    }
//
//    @Test
//    public void testAsyncRequestWithShutdown() throws Exception
//    {
//        long delay = 500;
//        CountDownLatch serverLatch = new CountDownLatch(1);
//        _statsHandler.setHandler(new AbstractHandler()
//        {
//            @Override
//            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
//            {
//                AsyncContext asyncContext = request.startAsync();
//                asyncContext.setTimeout(0);
//                new Thread(() ->
//                {
//                    try
//                    {
//                        Thread.sleep(delay);
//                        asyncContext.complete();
//                    }
//                    catch (InterruptedException e)
//                    {
//                        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
//                        asyncContext.complete();
//                    }
//                }).start();
//                serverLatch.countDown();
//            }
//        });
//        _server.start();
//
//        String request = "GET / HTTP/1.1\r\n" +
//            "Host: localhost\r\n" +
//            "\r\n";
//        _connector.executeRequest(request);
//
//        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
//
//        Future<Void> shutdown = _statsHandler.shutdown();
//        assertFalse(shutdown.isDone());
//
//        Thread.sleep(delay / 2);
//        assertFalse(shutdown.isDone());
//
//        Thread.sleep(delay);
//        assertTrue(shutdown.isDone());
//    }

    // This handler is external to the statistics handler and it is used to ensure that statistics handler's
    // handle() is fully executed before asserting its values in the tests, to avoid race conditions with the
    // tests' code where the test executes but the statistics handler has not finished yet.
    private static class LatchHandler extends Handler.Wrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            CountDownLatch latch = _latch;
            Request.Processor processor = super.handle(request);
            return (rq, rs, callback) ->
            {
                try
                {
                    processor.process(rq, rs, callback);
                }
                finally
                {
                    latch.countDown();
                }
            };
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

    /**
     * when the first barrier is reached, process() has been entered;
     * when the second barrier is reached, the callback is succeeded;
     * when the third barrier is reached, process() is returning
     */
    private static class TripleBarrierHandlerProcessor extends Handler.Processor.Blocking
    {
        private final CyclicBarrier[] _barriers;

        public TripleBarrierHandlerProcessor(CyclicBarrier[] barriers)
        {
            if (barriers.length != 3)
                throw new IllegalArgumentException();
            _barriers = barriers;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            try
            {
                _barriers[0].await();
                _barriers[1].await();
                callback.succeeded();
                _barriers[2].await();
            }
            catch (Throwable x)
            {
                Thread.currentThread().interrupt();
                callback.failed(x);
                throw new IOException(x);
            }
        }
    }
}
