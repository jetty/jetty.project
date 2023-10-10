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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends EventsHandler
{
    private final CounterStatistic _requestStats = new CounterStatistic(); // how many requests are being handled (full lifecycle)
    private final SampleStatistic _requestTimeStats = new SampleStatistic(); // latencies of requests (full lifecycle)
    private final CounterStatistic _handlingStats = new CounterStatistic(); // how many requests are in handle
    private final SampleStatistic _handlingTimeStats = new SampleStatistic(); // latencies of requests in handle
    private final LongAdder _errors = new LongAdder();
    private final LongAdder _handlingFailures = new LongAdder();
    private final LongAdder _responses1xx = new LongAdder();
    private final LongAdder _responses2xx = new LongAdder();
    private final LongAdder _responses3xx = new LongAdder();
    private final LongAdder _responses4xx = new LongAdder();
    private final LongAdder _responses5xx = new LongAdder();
    private final LongAdder _bytesRead = new LongAdder();
    private final LongAdder _bytesWritten = new LongAdder();
    private long _startTime = NanoTime.now();

    public StatisticsHandler()
    {
    }

    public StatisticsHandler(Handler handler)
    {
        super(handler);
    }

    @Override
    protected void doStart() throws Exception
    {
        reset();
        super.doStart();
    }

    @Override
    protected void onBeforeHandling(Request request)
    {
        _requestStats.increment();
        _handlingStats.increment();
    }

    @Override
    protected void onAfterHandling(Request request, boolean handled, Throwable failure)
    {
        if (failure != null)
            _handlingFailures.increment();
        _handlingStats.decrement();
        _handlingTimeStats.record(NanoTime.since(request.getBeginNanoTime()));
    }

    @Override
    protected void onRequestRead(Request request, Content.Chunk chunk)
    {
        if (chunk != null)
            _bytesRead.add(chunk.remaining());
    }

    @Override
    protected void onResponseBegin(Request request, int status, HttpFields headers)
    {
        switch (status / 100)
        {
            case 1 -> _responses1xx.increment();
            case 2 -> _responses2xx.increment();
            case 3 -> _responses3xx.increment();
            case 4 -> _responses4xx.increment();
            case 5 -> _responses5xx.increment();
        }
    }

    @Override
    protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
    {
        int length = BufferUtil.length(content);
        if (length > 0)
            _bytesWritten.add(length);
    }

    @Override
    protected void onComplete(Request request, Throwable failure)
    {
        if (failure != null)
            _errors.increment();
        _requestTimeStats.record(NanoTime.since(request.getBeginNanoTime()));
        _requestStats.decrement();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            Dumpable.named("requestStats", _requestStats),
            Dumpable.named("requestTimeStats", _requestTimeStats),
            Dumpable.named("handlingStats", _handlingStats),
            Dumpable.named("handlingTimeStats", _handlingTimeStats),
            Dumpable.named("errors", _errors),
            Dumpable.named("handlingFailures", _handlingFailures),
            Dumpable.named("1xxResponses", _responses1xx),
            Dumpable.named("2xxResponses", _responses2xx),
            Dumpable.named("3xxResponses", _responses3xx),
            Dumpable.named("4xxResponses", _responses4xx),
            Dumpable.named("5xxResponses", _responses5xx),
            Dumpable.named("bytesRead", _bytesRead),
            Dumpable.named("bytesWritten", _bytesWritten)
        );
    }

    @ManagedOperation(value = "resets the statistics", impact = "ACTION")
    public void reset()
    {
        _startTime = NanoTime.now();
        _requestStats.reset();
        _requestTimeStats.reset();
        _handlingStats.reset();
        _handlingTimeStats.reset();
        _errors.reset();
        _handlingFailures.reset();
        _responses1xx.reset();
        _responses2xx.reset();
        _responses3xx.reset();
        _responses4xx.reset();
        _responses5xx.reset();
        _bytesRead.reset();
        _bytesWritten.reset();
    }

    @ManagedAttribute("number of requests")
    public int getRequests()
    {
        return (int)_requestStats.getTotal();
    }

    @ManagedAttribute("number of requests currently active")
    public int getRequestsActive()
    {
        return (int)_requestStats.getCurrent();
    }

    @ManagedAttribute("maximum number of active requests")
    public int getRequestsActiveMax()
    {
        return (int)_requestStats.getMax();
    }

    @ManagedAttribute("number of handlings")
    public int getHandlings()
    {
        return (int)_handlingStats.getTotal();
    }

    @ManagedAttribute("number of handlings currently active")
    public int getHandlingsActive()
    {
        return (int)_handlingStats.getCurrent();
    }

    @ManagedAttribute("maximum number of active handlings")
    public int getHandlingsActiveMax()
    {
        return (int)_handlingStats.getMax();
    }

    @ManagedAttribute("maximum time spend in handling (in ns)")
    public long getHandlingsTimeMax()
    {
        return _handlingTimeStats.getMax();
    }

    @ManagedAttribute("total time spent in handling (in ns)")
    public long getHandlingsTimeTotal()
    {
        return _handlingTimeStats.getTotal();
    }

    @ManagedAttribute("mean time spent in handling (in ns)")
    public double getHandlingsTimeMean()
    {
        return _handlingTimeStats.getMean();
    }

    @ManagedAttribute("standard deviation for dispatch handling (in ns)")
    public double getDispatchedTimeStdDev()
    {
        return _handlingTimeStats.getStdDev();
    }

    @ManagedAttribute("number of async errors that occurred")
    public int getErrors()
    {
        return _errors.intValue();
    }

    @ManagedAttribute("number of requests with 1xx response status")
    public int getResponses1xx()
    {
        return _responses1xx.intValue();
    }

    @ManagedAttribute("number of requests with 2xx response status")
    public int getResponses2xx()
    {
        return _responses2xx.intValue();
    }

    @ManagedAttribute("number of requests with 3xx response status")
    public int getResponses3xx()
    {
        return _responses3xx.intValue();
    }

    @ManagedAttribute("number of requests with 4xx response status")
    public int getResponses4xx()
    {
        return _responses4xx.intValue();
    }

    @ManagedAttribute("number of requests with 5xx response status")
    public int getResponses5xx()
    {
        return _responses5xx.intValue();
    }

    @ManagedAttribute("number of requests that threw an exception during handling")
    public int getHandlingFailures()
    {
        return _handlingFailures.intValue();
    }

    @ManagedAttribute("total time spend in all request execution (in ns)")
    public long getRequestTimeTotal()
    {
        return _requestTimeStats.getTotal();
    }

    @ManagedAttribute("maximum time spend executing requests (in ns)")
    public long getRequestTimeMax()
    {
        return _requestTimeStats.getMax();
    }

    @ManagedAttribute("mean time spent executing requests (in ns)")
    public double getRequestTimeMean()
    {
        return _requestTimeStats.getMean();
    }

    @ManagedAttribute("standard deviation for request execution (in ns)")
    public double getRequestTimeStdDev()
    {
        return _requestTimeStats.getStdDev();
    }

    @ManagedAttribute("bytes read count")
    public long getBytesRead()
    {
        return _bytesRead.longValue();
    }

    @ManagedAttribute("bytes written count")
    public long getBytesWritten()
    {
        return _bytesWritten.longValue();
    }

    @ManagedAttribute("time in nanoseconds stats have been collected for")
    public long getStatsOnNs()
    {
        return NanoTime.since(_startTime);
    }

    /**
     * Checks that the wrapped handler can read/write at a minimal rate of N bytes per second.
     * When reading or writing does not conform to the specified rates, this handler prevents
     * further reads or writes by making them immediately fail.
     */
    public static class MinimumDataRateHandler extends StatisticsHandler
    {
        private final long _minimumReadRate;
        private final long _minimumWriteRate;

        /**
         * Creates a {@code MinimumDataRateHandler} with the specified read and write rates.
         * @param minimumReadRate the minimum number of bytes to be read per second, or 0 for not checking the read rate.
         * @param minimumWriteRate the minimum number of bytes to be written per second, or 0 for not checking the write rate.
         */
        public MinimumDataRateHandler(long minimumReadRate, long minimumWriteRate)
        {
            this(null, minimumReadRate, minimumWriteRate);
        }

        /**
         * Creates a {@code MinimumDataRateHandler} with the specified read and write rates.
         *
         * @param handler the handler to wrap.
         * @param minimumReadRate the minimum number of bytes to be read per second, or 0 for not checking the read rate.
         * @param minimumWriteRate the minimum number of bytes to be written per second, or 0 for not checking the write rate.
         */
        public MinimumDataRateHandler(Handler handler, long minimumReadRate, long minimumWriteRate)
        {
            super(handler);
            _minimumReadRate = minimumReadRate;
            _minimumWriteRate = minimumWriteRate;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            MinimumDataRateRequest wrappedRequest = new MinimumDataRateRequest(request);
            MinimumDataRateResponse wrappedResponse = new MinimumDataRateResponse(wrappedRequest, response);
            return super.handle(wrappedRequest, wrappedResponse, callback);
        }

        protected class MinimumDataRateRequest extends Request.Wrapper
        {
            private Content.Chunk _errorContent;

            private MinimumDataRateRequest(Request request)
            {
                super(request);
            }

            private long dataRatePerSecond(long dataCount)
            {
                if (dataCount == 0L)
                    return 0L;
                long delayInNs = NanoTime.since(getHeadersNanoTime());
                // If you read 1 byte or more in 0ns or less, you have infinite bandwidth.
                if (delayInNs <= 0L)
                    return Long.MAX_VALUE;
                return dataCount * 1_000_000_000 / delayInNs;
            }

            @Override
            public void demand(Runnable demandCallback)
            {
                if (_minimumReadRate > 0)
                {
                    long rr = dataRatePerSecond(getBytesRead());
                    if (rr < _minimumReadRate)
                    {
                        _errorContent = Content.Chunk.from(new TimeoutException("read rate is too low: " + rr));
                        demandCallback.run();
                        return;
                    }
                }
                super.demand(demandCallback);
            }

            @Override
            public Content.Chunk read()
            {
                return _errorContent != null ? _errorContent : super.read();
            }
        }

        protected class MinimumDataRateResponse extends Response.Wrapper
        {
            public MinimumDataRateResponse(MinimumDataRateRequest request, Response wrapped)
            {
                super(request, wrapped);
            }

            @Override
            public MinimumDataRateRequest getRequest()
            {
                return (MinimumDataRateRequest)super.getRequest();
            }

            @Override
            public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
            {
                if (_minimumWriteRate > 0)
                {
                    long bytesWritten = getBytesWritten();
                    if (bytesWritten > 0L)
                    {
                        long wr = getRequest().dataRatePerSecond(bytesWritten);
                        if (wr < _minimumWriteRate)
                        {
                            TimeoutException cause = new TimeoutException("write rate is too low: " + wr);
                            getRequest()._errorContent = Content.Chunk.from(cause);
                            callback.failed(cause);
                            return;
                        }
                    }
                }
                super.write(last, byteBuffer, callback);
            }
        }
    }
}
