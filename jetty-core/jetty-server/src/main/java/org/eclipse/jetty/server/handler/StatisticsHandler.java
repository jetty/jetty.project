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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
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

public class StatisticsHandler extends Handler.Wrapper
{
    private final CounterStatistic _requestStats = new CounterStatistic(); // how many requests are being processed (full lifecycle)
    private final SampleStatistic _requestTimeStats = new SampleStatistic(); // latencies of requests (full lifecycle)
    private final LongAdder _processingErrors = new LongAdder();
    private final LongAdder _responses1xx = new LongAdder();
    private final LongAdder _responses2xx = new LongAdder();
    private final LongAdder _responses3xx = new LongAdder();
    private final LongAdder _responses4xx = new LongAdder();
    private final LongAdder _responses5xx = new LongAdder();
    private final LongAdder _bytesRead = new LongAdder();
    private final LongAdder _bytesWritten = new LongAdder();

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        StatisticsRequest statisticsRequest = newStatisticsRequest(request);
        try
        {
            if (next.process(statisticsRequest, response, callback))
                return true;
            statisticsRequest.unProcessed();
            return false;
        }
        catch (Throwable t)
        {
            _processingErrors.increment();
            throw t;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            Dumpable.named("requestStats", _requestStats),
            Dumpable.named("requestTimeStats", _requestTimeStats),
            Dumpable.named("processingErrors", _processingErrors),
            Dumpable.named("1xxResponses", _responses1xx),
            Dumpable.named("2xxResponses", _responses2xx),
            Dumpable.named("3xxResponses", _responses3xx),
            Dumpable.named("4xxResponses", _responses4xx),
            Dumpable.named("5xxResponses", _responses5xx),
            Dumpable.named("bytesRead", _bytesRead),
            Dumpable.named("bytesWritten", _bytesWritten)
        );
    }

    protected StatisticsRequest newStatisticsRequest(Request request)
    {
        return new StatisticsRequest(request);
    }

    @ManagedOperation(value = "resets the statistics", impact = "ACTION")
    public void reset()
    {
        _requestStats.reset();
        _requestTimeStats.reset();
        _processingErrors.reset();
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

    @ManagedAttribute("number of requests that threw an exception during processing")
    public int getProcessingErrors()
    {
        return _processingErrors.intValue();
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

    protected class StatisticsRequest extends Request.Wrapper
    {
        private StatisticsRequest(Request request)
        {
            super(request);
            _requestStats.increment();
            addHttpStreamWrapper(this::asHttpStream);
        }

        public HttpStream asHttpStream(HttpStream httpStream)
        {
            return new StatisticsHttpStream(httpStream);
        }

        /**
         * Creating a {@link StatisticsRequest} increments the {@link #getRequests() request counter} before its gets a chance
         * of figuring out if the request is going to be handled by the {@link StatisticsHandler#getHandler() wrapped handler}.
         * In case the wrapped handler did not process the request, calling this method decrements the request counter to
         * compensate for the unneeded increment.
         */
        protected void unProcessed()
        {
            _requestStats.decrement();
        }

        protected class StatisticsHttpStream extends HttpStream.Wrapper
        {
            public StatisticsHttpStream(HttpStream httpStream)
            {
                super(httpStream);
            }

            @Override
            public Content.Chunk read()
            {
                Content.Chunk chunk = super.read();
                if (chunk != null)
                    _bytesRead.add(chunk.remaining());
                return chunk;
            }

            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                if (response != null)
                {
                    switch (response.getStatus() / 100)
                    {
                        case 1 -> _responses1xx.increment();
                        case 2 -> _responses2xx.increment();
                        case 3 -> _responses3xx.increment();
                        case 4 -> _responses4xx.increment();
                        case 5 -> _responses5xx.increment();
                    }
                }
                int length = BufferUtil.length(content);
                if (length > 0)
                    _bytesWritten.add(length);
                super.send(request, response, last, content, callback);
            }

            @Override
            public void succeeded()
            {
                _requestStats.decrement();
                _requestTimeStats.record(NanoTime.since(getNanoTime()));
                super.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                _requestStats.decrement();
                _requestTimeStats.record(NanoTime.since(getNanoTime()));
                super.failed(x);
            }
        }
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
            _minimumReadRate = minimumReadRate;
            _minimumWriteRate = minimumWriteRate;
        }

        @Override
        protected StatisticsRequest newStatisticsRequest(Request request)
        {
            return new MinimumDataRateRequest(request);
        }

        protected class MinimumDataRateRequest extends StatisticsRequest
        {
            private Content.Chunk.Error _errorContent;

            private MinimumDataRateRequest(Request request)
            {
                super(request);
            }

            private long dataRatePerSecond(long dataCount)
            {
                if (dataCount == 0L)
                    return 0L;
                long delayInNs = NanoTime.since(getNanoTime());
                // If you read 1 byte or more in 0ns or less, you have infinite bandwidth.
                if (delayInNs <= 0L)
                    return Long.MAX_VALUE;
                // The transformation of delayInNs to delayInSeconds must be done as floating
                // point otherwise the result is going to be 0 for any delay below 1s.
                float delayInSeconds = delayInNs / 1_000_000_000F;
                return (long)(dataCount / delayInSeconds);
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

            @Override
            public HttpStream asHttpStream(HttpStream httpStream)
            {
                return new StatisticsHttpStream(httpStream)
                {
                    @Override
                    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
                    {
                        if (_minimumWriteRate > 0)
                        {
                            long bytesWritten = getBytesWritten();
                            if (bytesWritten > 0L)
                            {
                                long wr = dataRatePerSecond(bytesWritten);
                                if (wr < _minimumWriteRate)
                                {
                                    TimeoutException cause = new TimeoutException("write rate is too low: " + wr);
                                    _errorContent = Content.Chunk.from(cause);
                                    callback.failed(cause);
                                    return;
                                }
                            }
                        }
                        super.send(request, response, last, content, callback);
                    }
                };
            }
        }
    }
}
