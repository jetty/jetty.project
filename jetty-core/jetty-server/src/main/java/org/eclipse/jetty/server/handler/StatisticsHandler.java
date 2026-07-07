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
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends EventsHandler
{
    private final CounterStatistic _requestStats = new CounterStatistic(); // how many requests are being handled (full lifecycle)
    private final SampleStatistic _requestTimeStats = new SampleStatistic(); // latencies of requests (full lifecycle)
    private final CounterStatistic _handleStats = new CounterStatistic(); // how many requests are in handle()
    private final SampleStatistic _handleTimeStats = new SampleStatistic(); // latencies of requests in handle()
    private final LongAdder _failures = new LongAdder();
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
        _handleStats.increment();
    }

    @Override
    protected void onAfterHandling(Request request, boolean handled, Throwable failure)
    {
        if (failure != null)
            _handlingFailures.increment();
        _handleStats.decrement();
        _handleTimeStats.record(NanoTime.since(request.getHeadersNanoTime()));
    }

    @Override
    protected void onRequestRead(Request request, Content.Chunk chunk)
    {
        if (chunk != null)
            _bytesRead.add(chunk.remaining());
    }

    @Override
    protected void onResponseWriteComplete(Request request, boolean last, ReadableBuffer content, Throwable failure)
    {
        if (failure == null)
        {
            long length = content == null ? 0L : content.remaining();
            if (length > 0)
                _bytesWritten.add(length);
        }
    }

    @Override
    protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
    {
        if (failure != null)
            _failures.increment();
        _requestTimeStats.record(NanoTime.since(request.getBeginNanoTime()));
        _requestStats.decrement();
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
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            Dumpable.named("requestStats", _requestStats),
            Dumpable.named("requestTimeStats", _requestTimeStats),
            Dumpable.named("handleStats", _handleStats),
            Dumpable.named("handleTimeStats", _handleTimeStats),
            Dumpable.named("failures", _failures),
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
        _handleStats.reset();
        _handleTimeStats.reset();
        _failures.reset();
        _handlingFailures.reset();
        _responses1xx.reset();
        _responses2xx.reset();
        _responses3xx.reset();
        _responses4xx.reset();
        _responses5xx.reset();
        _bytesRead.reset();
        _bytesWritten.reset();
    }

    /**
     * @deprecated use {@link #getRequestTotal()} instead.
     */
    @Deprecated
    @ManagedAttribute("number of requests")
    public int getRequests()
    {
        return (int)_requestStats.getTotal();
    }

    @ManagedAttribute("total number of requests")
    public int getRequestTotal()
    {
        return (int)_requestStats.getTotal();
    }

    @ManagedAttribute("current number of active requests")
    public int getRequestsActive()
    {
        return (int)_requestStats.getCurrent();
    }

    @ManagedAttribute("maximum number of active requests")
    public int getRequestsActiveMax()
    {
        return (int)_requestStats.getMax();
    }

    @ManagedAttribute("total time spent in request execution (in ns)")
    public long getRequestTimeTotal()
    {
        return _requestTimeStats.getTotal();
    }

    @ManagedAttribute("maximum request execution time (in ns)")
    public long getRequestTimeMax()
    {
        return _requestTimeStats.getMax();
    }

    @ManagedAttribute("mean request execution time (in ns)")
    public double getRequestTimeMean()
    {
        return _requestTimeStats.getMean();
    }

    @ManagedAttribute("standard deviation for request execution time (in ns)")
    public double getRequestTimeStdDev()
    {
        return _requestTimeStats.getStdDev();
    }

    @ManagedAttribute("total number of calls to handle()")
    public int getHandleTotal()
    {
        return (int)_handleStats.getTotal();
    }

    @ManagedAttribute("current number of requests in handle()")
    public int getHandleActive()
    {
        return (int)_handleStats.getCurrent();
    }

    @ManagedAttribute("maximum number of requests in handle()")
    public int getHandleActiveMax()
    {
        return (int)_handleStats.getMax();
    }

    @ManagedAttribute("maximum handle() execution time (in ns)")
    public long getHandleTimeMax()
    {
        return _handleTimeStats.getMax();
    }

    @ManagedAttribute("total time spent in handle() execution (in ns)")
    public long getHandleTimeTotal()
    {
        return _handleTimeStats.getTotal();
    }

    @ManagedAttribute("mean handle() execution time (in ns)")
    public double getHandleTimeMean()
    {
        return _handleTimeStats.getMean();
    }

    @ManagedAttribute("standard deviation for handle() execution time (in ns)")
    public double getHandleTimeStdDev()
    {
        return _handleTimeStats.getStdDev();
    }

    @ManagedAttribute("number of failed requests")
    public int getFailures()
    {
        return _failures.intValue();
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

    @ManagedAttribute("number of requests that threw an exception from handle()")
    public int getHandlingFailures()
    {
        return _handlingFailures.intValue();
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

    @ManagedAttribute("duration for which statistics have been collected")
    public Duration getStatisticsDuration()
    {
        return Duration.ofNanos(NanoTime.since(_startTime));
    }

    /**
     * @deprecated use {@link org.eclipse.jetty.server.handler.MinimumDataRateHandler} instead.
     */
    @Deprecated(since = "12.1.11", forRemoval = true)
    public static class MinimumDataRateHandler extends org.eclipse.jetty.server.handler.MinimumDataRateHandler
    {
        public MinimumDataRateHandler(long minimumReadRate, long minimumWriteRate)
        {
            this(null, minimumReadRate, minimumWriteRate);
        }

        public MinimumDataRateHandler(Handler handler, long minimumReadRate, long minimumWriteRate)
        {
            super(handler, minimumReadRate, minimumWriteRate);
        }
    }
}
