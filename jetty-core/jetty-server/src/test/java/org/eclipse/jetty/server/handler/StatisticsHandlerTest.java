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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.awaitility.Awaitility;
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

        _statsHandler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> process(request, response, callback));
                        return true;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        Long rr = (Long)request.getAttribute("o.e.j.s.h.StatsHandler.dataReadRate");
                        readRate.set(rr);
                        //System.err.println("over; read rate=" + rr + " b/s");
                        callback.succeeded();
                        return true;
                    }
                }
            }
        });
        _server.start();

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Content-Length: 1000\r
            \r
            """;

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

        _statsHandler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
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
                return true;
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

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        _connector.executeRequest(request);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(writeRate.get(), allOf(greaterThan(600L), lessThan(1100L)));
    }

    @Test
    public void testMinimumDataReadRateHandler() throws Exception
    {
        StatisticsHandler.MinimumDataRateHandler mdrh = new StatisticsHandler.MinimumDataRateHandler(1100, 0);
        mdrh.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> process(request, response, callback));
                        return true;
                    }

                    if (chunk instanceof Content.Chunk.Error errorContent)
                    {
                        callback.failed(errorContent.getCause());
                        return true;
                    }

                    chunk.release();
                    if (chunk.isLast())
                    {
                        callback.succeeded();
                        return true;
                    }
                }
            }
        });

        _latchHandler.setHandler(mdrh);
        _server.start();

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Content-Length: 1000\r
            \r
            """;

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
        mdrh.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
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
                return true;
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

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        // 1st request
        _connector.executeRequest(request);

        barrier[0].await();
        assertEquals(1, _statistics.getConnections());
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
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
        assertEquals(1, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
        assertEquals(1, _statsHandler.getResponses2xx());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();
        barrier[2].reset();

        // 2nd request
        _connector.executeRequest(request);

        barrier[0].await();
        assertEquals(2, _statistics.getConnections());
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
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
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(1, _statsHandler.getProcessingsMax());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

    @Test
    public void testTwoRequestsInParallel() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(3), new CyclicBarrier(3), new CyclicBarrier(3)};
        _latchHandler.reset(2);
        _statsHandler.setHandler(new TripleBarrierHandlerProcessor(barrier));
        _server.start();

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        _connector.executeRequest(request);
        _connector.executeRequest(request);

        barrier[0].await();
        assertEquals(2, _statistics.getConnections());
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(2, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());
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
        assertEquals(2, _statsHandler.getProcessings());
        assertEquals(0, _statsHandler.getProcessingsActive());
        assertEquals(2, _statsHandler.getProcessingsMax());
        assertEquals(2, _statsHandler.getResponses2xx());
    }

    @Test
    public void testProcessingIncrementThenAcceptingIncrement() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                barrier[0].await();
                barrier[1].await();

                barrier[2].await();
                barrier[3].await();
                
                callback.succeeded();
                
                barrier[4].await();
                return true;
            }
        });
        _server.start();

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endp = _connector.executeRequest(request))
        {
            barrier[0].await();

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getProcessings());
            assertEquals(1, _statsHandler.getProcessingsActive());
            assertEquals(1, _statsHandler.getProcessingsMax());
            barrier[1].await();
            barrier[2].await();

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getProcessings());
            assertEquals(1, _statsHandler.getProcessingsActive());
            assertEquals(1, _statsHandler.getProcessingsMax());
            barrier[3].await();
            barrier[4].await();

            String response = endp.getResponse();
            assertThat(response, containsString(" 200 OK"));
            await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(0, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getProcessings());
            assertEquals(0, _statsHandler.getProcessingsActive());
            assertEquals(1, _statsHandler.getProcessingsMax());
        }
    }

    @Test
    public void testThrownInProcess() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                throw new IllegalStateException("expected");
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            String response = _connector.getResponse(request);
            assertThat(response, containsString("HTTP/1.1 500 Server Error"));
        }

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(0, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(1, _statsHandler.getResponses5xx());
        assertEquals(1, _statsHandler.getProcessingErrors());
    }

    @Test
    public void testThrownInProcessAfterCallback() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                throw new IllegalStateException("expected");
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            String response = _connector.getResponse(request);
            assertThat(response, containsString("HTTP/1.1 200 OK"));
        }

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> _statsHandler.getRequestsActive() == 0);
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(1, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(0, _statsHandler.getResponses5xx());
        assertEquals(1, _statsHandler.getProcessingErrors());
    }

    @Test
    public void testHandlingProcessingTime() throws Exception
    {
        final long acceptingTime = 250;
        final long acceptedTime = 500;
        final long wastedTime = 250;
        final long requestTime = acceptingTime + acceptedTime;
        final long processTime = acceptingTime + acceptedTime + wastedTime;
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};

        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                barrier[0].await();
                Thread.sleep(acceptingTime);
                try
                {
                    barrier[1].await();
                    Thread.sleep(acceptedTime);
                    callback.succeeded();
                }
                finally
                {
                    try
                    {
                        barrier[2].await();
                        Thread.sleep(wastedTime);
                    }
                    catch (Throwable x)
                    {
                        callback.failed(x);
                    }
                }
                return true;
            }
        });
        _server.start();

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endp = _connector.executeRequest(request))
        {
            barrier[0].await();

            assertEquals(1, _statistics.getConnections());

            barrier[1].await();
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            barrier[2].await();
            assertTrue(_latchHandler.await());
            await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
            String response = endp.getResponse();
            assertThat(response, containsString(" 200 OK"));

            assertEquals(1, _statsHandler.getRequests());
            assertEquals(0, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getResponses2xx());

            _statsHandler.dumpStdErr();

            // TODO currently the wasted time is included in the request time and the accepted time, because those
            //      timers are stopped in the stream completion (rather than the callback completion), which is
            //      serialized on the return of the call to process.   Perhaps we should wrap the callback for
            //      those times?

            assertThat(_statsHandler.getRequestTimeTotal(), allOf(
                greaterThan(TimeUnit.MILLISECONDS.toNanos(requestTime + wastedTime) * 3 / 4),
                lessThan(TimeUnit.MILLISECONDS.toNanos(requestTime + wastedTime) * 5 / 4)));
            assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
            assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 1.0);

            assertThat(_statsHandler.getProcessingTimeTotal(), allOf(
                greaterThan(TimeUnit.MILLISECONDS.toNanos(processTime) * 3 / 4),
                lessThan(TimeUnit.MILLISECONDS.toNanos(processTime) * 5 / 4)));
            assertTrue(_statsHandler.getProcessingTimeTotal() < _statsHandler.getRequestTimeTotal());
            assertEquals(_statsHandler.getProcessingTimeTotal(), _statsHandler.getProcessingTimeMax());
            assertEquals(_statsHandler.getProcessingTimeTotal(), _statsHandler.getProcessingTimeMean(), 1.0);
        }
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
    private static class LatchHandler extends Handler.BaseWrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            CountDownLatch latch = _latch;
            try
            {
                return super.process(request, response, callback);
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

    /**
     * when the first barrier is reached, process() has been entered;
     * when the second barrier is reached, the callback is succeeded;
     * when the third barrier is reached, process() is returning
     */
    private static class TripleBarrierHandlerProcessor extends Handler.Abstract
    {
        private final CyclicBarrier[] _barriers;

        public TripleBarrierHandlerProcessor(CyclicBarrier[] barriers)
        {
            if (barriers.length != 3)
                throw new IllegalArgumentException();
            _barriers = barriers;
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
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
            return true;
        }
    }
}
