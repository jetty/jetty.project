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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
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
    public void testMinimumDataReadRateHandler() throws Exception
    {
        StatisticsHandler.MinimumDataRateHandler mdrh = new StatisticsHandler.MinimumDataRateHandler(1100, 0);
        mdrh.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> handle(request, response, callback));
                        return true;
                    }

                    if (Content.Chunk.isFailure(chunk))
                    {
                        callback.failed(chunk.getFailure());
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
        AtomicReference<String> messageRef = new AtomicReference<>();
        _server.setErrorHandler((request, response, callback) ->
        {
            messageRef.set((String)request.getAttribute(ErrorHandler.ERROR_MESSAGE));
            callback.succeeded();
            return true;
        });
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
            assertThat(messageRef.get(), startsWith("java.util.concurrent.TimeoutException: read rate is too low"));
        }
    }

    @Test
    public void testMinimumDataWriteRateHandler() throws Exception
    {
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        int expectedContentLength = 1000;
        StatisticsHandler.MinimumDataRateHandler mdrh = new StatisticsHandler.MinimumDataRateHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
                        exceptionRef.set(x);
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
        }, 0, 1000);

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
        assertThat(statusHolder.get(), is(500));
        assertThat(exceptionRef.get(), instanceOf(TimeoutException.class));
        assertThat(exceptionRef.get().getMessage(), startsWith("write rate is too low"));
    }

    @Test
    public void testTwoRequestsSerially() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new TripleBarrierHandler(barrier));
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
        assertEquals(1, _statsHandler.getHandleActive());
        assertEquals(1, _statsHandler.getHandleActiveMax());
        assertEquals(0, _statsHandler.getErrors());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(0, _statsHandler.getHandleActive());
        assertEquals(1, _statsHandler.getHandleActiveMax());
        assertEquals(1, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getErrors());

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
        assertEquals(1, _statsHandler.getHandleActive());
        assertEquals(1, _statsHandler.getHandleActiveMax());
        assertEquals(0, _statsHandler.getErrors());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(0, _statsHandler.getHandleActive());
        assertEquals(1, _statsHandler.getHandleActiveMax());
        assertEquals(2, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getErrors());
    }

    @Test
    public void testTwoRequestsInParallel() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(3), new CyclicBarrier(3), new CyclicBarrier(3)};
        _latchHandler.reset(2);
        _statsHandler.setHandler(new TripleBarrierHandler(barrier));
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
        assertEquals(2, _statsHandler.getHandleActive());
        assertEquals(2, _statsHandler.getHandleActiveMax());
        assertEquals(0, _statsHandler.getErrors());

        barrier[1].await();
        barrier[2].await();
        assertTrue(_latchHandler.await());
        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());
        assertEquals(0, _statsHandler.getHandleActive());
        assertEquals(2, _statsHandler.getHandleActiveMax());
        assertEquals(2, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getErrors());
    }

    @Test
    public void testHandlingIncrementThenAcceptingIncrement() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            assertEquals(1, _statsHandler.getHandleActive());
            barrier[1].await();
            barrier[2].await();

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getHandleActive());
            barrier[3].await();
            barrier[4].await();

            String response = endp.getResponse();
            assertThat(response, containsString(" 200 OK"));
            await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(0, _statsHandler.getRequestsActive());
            assertEquals(0, _statsHandler.getHandleActive());
        }
    }

    @Test
    public void testHandlingIncrementThenAsyncSuccessIncrement() throws Exception
    {
        CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                barrier[0].await();
                barrier[1].await();

                barrier[2].await();
                barrier[3].await();

                new Thread(() ->
                {
                    try
                    {
                        barrier[4].await();
                        barrier[5].await();
                        callback.succeeded();
                    }
                    catch (Throwable x)
                    {
                        callback.failed(x);
                    }
                }).start();

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
            assertEquals(1, _statsHandler.getHandleActive());
            barrier[1].await();
            barrier[2].await();

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            assertEquals(1, _statsHandler.getHandleActive());
            barrier[3].await();
            barrier[4].await();

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(1, _statsHandler.getRequestsActive());
            assertEquals(0, _statsHandler.getHandleActive());
            barrier[5].await();

            String response = endp.getResponse();
            assertThat(response, containsString(" 200 OK"));
            await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));

            assertEquals(1, _statistics.getConnections());
            assertEquals(1, _statsHandler.getRequests());
            assertEquals(0, _statsHandler.getRequestsActive());
            assertEquals(0, _statsHandler.getHandleActive());
        }
    }

    @Test
    public void testThrownInHandle() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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

        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, is(0));
        assertEquals(1, _statsHandler.getRequests(), "stats.requests");
        assertEquals(1, _statsHandler.getRequestsActiveMax(), "stats.requestsActiveMax");
        assertEquals(1, _statsHandler.getHandleActiveMax(), "stats.dispatchedActiveMax");

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx(), "stats.responses1xx");
        assertEquals(0, _statsHandler.getResponses2xx(), "stats.responses2xx");
        assertEquals(0, _statsHandler.getResponses3xx(), "stats.responses3xx");
        assertEquals(0, _statsHandler.getResponses4xx(), "stats.responses4xx");
        assertEquals(1, _statsHandler.getResponses5xx(), "stats.responses5xx");
        assertEquals(1, _statsHandler.getHandlingFailures(), "stats.handlingFailures");
        assertEquals(1, _statsHandler.getErrors(), "stats.errors");
    }

    @Test
    public void testFailCallbackInHandle() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.failed(new IllegalStateException("expected"));
                return true;
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

        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, is(0));
        assertEquals(1, _statsHandler.getRequests(), "stats.requests");
        assertEquals(1, _statsHandler.getRequestsActiveMax(), "stats.requestsActiveMax");
        assertEquals(1, _statsHandler.getHandleActiveMax(), "stats.dispatchedActiveMax");

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx(), "stats.responses1xx");
        assertEquals(0, _statsHandler.getResponses2xx(), "stats.responses2xx");
        assertEquals(0, _statsHandler.getResponses3xx(), "stats.responses3xx");
        assertEquals(0, _statsHandler.getResponses4xx(), "stats.responses4xx");
        assertEquals(1, _statsHandler.getResponses5xx(), "stats.responses5xx");
        assertEquals(0, _statsHandler.getHandlingFailures(), "stats.handlingFailures");
        assertEquals(1, _statsHandler.getErrors(), "stats.errors");
    }

    @Test
    public void testFailCallbackAfterHandle() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e)
                    {
                        // ignore
                    }
                    callback.failed(new IllegalStateException("expected"));
                }).start();
                return true;
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

        await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, is(0));
        assertEquals(1, _statsHandler.getRequests(), "stats.requests");
        assertEquals(1, _statsHandler.getRequestsActiveMax(), "stats.requestsActiveMax");
        assertEquals(1, _statsHandler.getHandleActiveMax(), "stats.dispatchedActiveMax");

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx(), "stats.responses1xx");
        assertEquals(0, _statsHandler.getResponses2xx(), "stats.responses2xx");
        assertEquals(0, _statsHandler.getResponses3xx(), "stats.responses3xx");
        assertEquals(0, _statsHandler.getResponses4xx(), "stats.responses4xx");
        assertEquals(1, _statsHandler.getResponses5xx(), "stats.responses5xx");
        assertEquals(0, _statsHandler.getHandlingFailures(), "stats.handlingFailures");
        assertEquals(1, _statsHandler.getErrors(), "stats.errors");
    }

    @Test
    public void testThrownInHandleAfterCallback() throws Exception
    {
        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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

        await().atMost(Duration.ofSeconds(5)).until(_statsHandler::getRequestsActive, is(0));
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        assertEquals(0, _statsHandler.getHandleActive());
        assertEquals(1, _statsHandler.getHandleActiveMax());

        // We get no recorded status, but we get a recorded thrown response.
        assertEquals(0, _statsHandler.getResponses1xx());
        assertEquals(1, _statsHandler.getResponses2xx());
        assertEquals(0, _statsHandler.getResponses3xx());
        assertEquals(0, _statsHandler.getResponses4xx());
        assertEquals(0, _statsHandler.getResponses5xx());
        assertEquals(1, _statsHandler.getHandlingFailures());
        assertEquals(0, _statsHandler.getErrors(), "stats.errors");
    }

    @Test
    public void testHandlingTime() throws Exception
    {
        final long acceptingTime = 250;
        final long acceptedTime = 500;
        final long wastedTime = 250;
        final long requestTime = acceptingTime + acceptedTime;
        final long handleTime = acceptingTime + acceptedTime + wastedTime;
        final CyclicBarrier[] barrier = {new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};

        _statsHandler.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            assertEquals(1, _statsHandler.getHandleActive());
            barrier[2].await();
            assertTrue(_latchHandler.await());
            await().atMost(5, TimeUnit.SECONDS).until(_statsHandler::getRequestsActive, equalTo(0));
            String response = endp.getResponse();
            assertThat(response, containsString(" 200 OK"));

            assertEquals(1, _statsHandler.getRequests());
            assertEquals(0, _statsHandler.getRequestsActive());
            assertEquals(0, _statsHandler.getHandleActive());
            assertEquals(1, _statsHandler.getResponses2xx());

            _statsHandler.dumpStdErr();

            // TODO currently the wasted time is included in the request time and the accepted time, because those
            //      timers are stopped in the stream completion (rather than the callback completion), which is
            //      serialized on the return of the call to handle.   Perhaps we should wrap the callback for
            //      those times?

            assertThat(_statsHandler.getRequestTimeTotal(), allOf(
                greaterThan(TimeUnit.MILLISECONDS.toNanos(requestTime + wastedTime) * 3 / 4),
                lessThan(TimeUnit.MILLISECONDS.toNanos(requestTime + wastedTime) * 5 / 4)));
            assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMax());
            assertEquals(_statsHandler.getRequestTimeTotal(), _statsHandler.getRequestTimeMean(), 1.0);
            assertThat(_statsHandler.getHandleTimeTotal(), allOf(
                greaterThan(TimeUnit.MILLISECONDS.toNanos(handleTime + wastedTime) * 3 / 4),
                lessThan(TimeUnit.MILLISECONDS.toNanos(handleTime + wastedTime) * 5 / 4)));
            assertEquals(_statsHandler.getHandleTimeTotal(), _statsHandler.getHandleTimeMax());
            assertEquals(_statsHandler.getHandleTimeTotal(), _statsHandler.getHandleTimeMean(), 1.0);
        }
    }

    @Test
    public void testStatsOn() throws Exception
    {
        _statsHandler.reset();
        Thread.sleep(500);
        assertThat(_statsHandler.getStatisticsDuration().toMillis(), greaterThanOrEqualTo(500L));
        _statsHandler.reset();
        assertThat(_statsHandler.getStatisticsDuration().toMillis(), lessThan(500L));
    }

    // This handler is external to the statistics handler and it is used to ensure that statistics handler's
    // handle() is fully executed before asserting its values in the tests, to avoid race conditions with the
    // tests' code where the test executes but the statistics handler has not finished yet.
    private static class LatchHandler extends Handler.Wrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            CountDownLatch latch = _latch;
            try
            {
                return super.handle(request, response, callback);
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
     * when the first barrier is reached, handle() has been entered;
     * when the second barrier is reached, the callback is succeeded;
     * when the third barrier is reached, handle() is returning
     */
    private static class TripleBarrierHandler extends Handler.Abstract
    {
        private final CyclicBarrier[] _barriers;

        public TripleBarrierHandler(CyclicBarrier[] barriers)
        {
            if (barriers.length != 3)
                throw new IllegalArgumentException();
            _barriers = barriers;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
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
