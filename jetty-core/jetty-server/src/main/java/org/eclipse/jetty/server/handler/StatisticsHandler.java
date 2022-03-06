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

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends Handler.Wrapper
{
    private final ConcurrentHashMap<String, Object> _connectionStats = new ConcurrentHashMap<>();

    private final CounterStatistic _requestStats = new CounterStatistic();
    private final CounterStatistic _handleStats = new CounterStatistic();
    private final CounterStatistic _processStats = new CounterStatistic();
    private final SampleStatistic _requestTimeStats = new SampleStatistic();
    private final SampleStatistic _handleTimeStats = new SampleStatistic();
    private final SampleStatistic _processTimeStats = new SampleStatistic();

    private final LongAdder _responses1xx = new LongAdder();
    private final LongAdder _responses2xx = new LongAdder();
    private final LongAdder _responses3xx = new LongAdder();
    private final LongAdder _responses4xx = new LongAdder();
    private final LongAdder _responses5xx = new LongAdder();

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        long beginTimeStamp = System.nanoTime();
        _connectionStats.computeIfAbsent(request.getConnectionMetaData().getId(), id ->
        {
            // TODO test this with localconnector endpoint that has multiple requests per connection.
            request.getHttpChannel().addConnectionCloseListener(x ->
            {
                // complete connections stats
                _connectionStats.remove(request.getConnectionMetaData().getId());
            });
            return "SomeConnectionStatsObject";
        });

        StatisticsRequest statisticsRequest = new StatisticsRequest(request);
        _handleStats.increment();
        _requestStats.increment();

        // TODO don't need to do this until we see a Processor
        request.getHttpChannel().addStreamWrapper(s -> new HttpStream.Wrapper(s)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
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
                        default ->
                        {
                        }
                    }
                }

                for (ByteBuffer b : content)
                {
                    statisticsRequest._bytesWritten.add(b.remaining());
                }

                super.send(response, last, callback, content);
            }

            @Override
            public Content readContent()
            {
                Content content =  super.readContent();
                if (content != null)
                    statisticsRequest._bytesRead.add(content.remaining());
                return content;
            }

            @Override
            public void succeeded()
            {
                super.succeeded();
                _processStats.decrement();
                _requestStats.decrement();
                _processTimeStats.record(System.nanoTime() - statisticsRequest._processStartTimeStamp);
                _requestTimeStats.record(System.nanoTime() - getNanoTimeStamp());
            }

            @Override
            public void failed(Throwable x)
            {
                super.failed(x);
                _processStats.decrement();
                _requestStats.decrement();
                _processTimeStats.record(System.nanoTime() - statisticsRequest._processStartTimeStamp);
                _requestTimeStats.record(System.nanoTime() - getNanoTimeStamp());
            }
        });

        Request.Processor processor = super.handle(statisticsRequest);
        _handleTimeStats.record(System.nanoTime() - beginTimeStamp);
        return processor == null ? null : statisticsRequest.wrapProcessor(processor);
    }

    private class StatisticsRequest extends Request.WrapperProcessor
    {
        private final LongAdder _bytesRead = new LongAdder();
        private final LongAdder _bytesWritten = new LongAdder();
        private long _processStartTimeStamp;

        private StatisticsRequest(Request request)
        {
            super(request);
        }

        // TODO make this wrapper optional. Only needed if requestLog asks for these attributes.
        @Override
        public Object getAttribute(String name)
        {
            // return hidden attributes for requestLog
            return switch (name)
            {
                // TODO class.getName + extra
                case "o.e.j.s.h.StatsHandler.bytesRead" -> _bytesRead;
                case "o.e.j.s.h.StatsHandler.bytesWritten" -> _bytesWritten;
                case "o.e.j.s.h.StatsHandler.spentTime" -> spentTimeNs();
                case "o.e.j.s.h.StatsHandler.dataReadRate" -> dataRatePerSecond(_bytesRead.longValue());
                case "o.e.j.s.h.StatsHandler.dataWriteRate" -> dataRatePerSecond(_bytesWritten.longValue());
                default -> super.getAttribute(name);
            };
        }

        private long dataRatePerSecond(long dataCount)
        {
            return (long)(dataCount / (spentTimeNs() / 1_000_000_000F));
        }

        private long spentTimeNs()
        {
            return System.nanoTime() - _processStartTimeStamp;
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            _processStats.increment();
            _processStartTimeStamp = System.nanoTime();
            super.process(this, response, callback);
        }
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

    @ManagedAttribute("")
    public int getHandlings()
    {
        return (int)_handleStats.getCurrent();
    }

    @ManagedAttribute("")
    public int getProcessings()
    {
        return (int)_processStats.getTotal();
    }

    @ManagedAttribute("")
    public int getProcessingsActive()
    {
        return (int)_processStats.getCurrent();
    }

    @ManagedAttribute("")
    public int getProcessingsMax()
    {
        return (int)_processStats.getMax();
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

    @ManagedAttribute("(in ns)")
    public long getHandlingTimeTotal()
    {
        return _handleTimeStats.getTotal();
    }

    @ManagedAttribute("(in ns)")
    public long getHandlingTimeMax()
    {
        return _handleTimeStats.getMax();
    }

    @ManagedAttribute("(in ns)")
    public double getHandlingTimeMean()
    {
        return _handleTimeStats.getMean();
    }

    @ManagedAttribute("(in ns)")
    public double getHandlingTimeStdDev()
    {
        return _handleTimeStats.getStdDev();
    }

    @ManagedAttribute("(in ns)")
    public long getProcessingTimeTotal()
    {
        return _processTimeStats.getTotal();
    }

    @ManagedAttribute("(in ns)")
    public long getProcessingTimeMax()
    {
        return _processTimeStats.getMax();
    }

    @ManagedAttribute("(in ns)")
    public double getProcessingTimeMean()
    {
        return _processTimeStats.getMean();
    }

    @ManagedAttribute("(in ns)")
    public double getProcessingTimeStdDev()
    {
        return _processTimeStats.getStdDev();
    }
}
