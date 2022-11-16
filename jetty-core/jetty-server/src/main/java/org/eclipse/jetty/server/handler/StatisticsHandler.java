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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
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
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends Handler.ProcessingWrapper<StatisticsHandler.StatisticsRequest, Response>
{
    private final Set<String> _connectionStats = ConcurrentHashMap.newKeySet();
    private final CounterStatistic _requestStats = new CounterStatistic();
    private final CounterStatistic _handleStats = new CounterStatistic();
    private final CounterStatistic _processStats = new CounterStatistic();
    private final SampleStatistic _requestTimeStats = new SampleStatistic();
    private final SampleStatistic _handleTimeStats = new SampleStatistic();
    private final SampleStatistic _processTimeStats = new SampleStatistic();
    private final LongAdder _processingErrors = new LongAdder();
    private final LongAdder _handlingErrors = new LongAdder();
    private final LongAdder _responses1xx = new LongAdder();
    private final LongAdder _responses2xx = new LongAdder();
    private final LongAdder _responses3xx = new LongAdder();
    private final LongAdder _responses4xx = new LongAdder();
    private final LongAdder _responses5xx = new LongAdder();

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        long beginNanoTime = NanoTime.now();
        _handleStats.increment();

        try
        {
            return super.handle(request);
        }
        catch (Throwable t)
        {
            _handlingErrors.increment();
            throw t;
        }
        finally
        {
            _handleStats.decrement();
            _handleTimeStats.record(NanoTime.since(beginNanoTime));
        }
    }

    @Override
    protected StatisticsRequest wrap(Request request)
    {
        return new StatisticsRequest(request);
    }

    @ManagedOperation(value = "resets the statistics", impact = "ACTION")
    public void reset()
    {
        _connectionStats.clear();
        _requestStats.reset();
        _handleStats.reset();
        _processStats.reset();
        _requestTimeStats.reset();
        _handleTimeStats.reset();
        _processTimeStats.reset();
        _processingErrors.reset();
        _handlingErrors.reset();
        _responses1xx.reset();
        _responses2xx.reset();
        _responses3xx.reset();
        _responses4xx.reset();
        _responses5xx.reset();
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

    @ManagedAttribute("number of requests that threw an exception during handling")
    public int getHandlingErrors()
    {
        return _handlingErrors.intValue();
    }

    @ManagedAttribute("number of requests that threw an exception during processing")
    public int getProcessingErrors()
    {
        return _processingErrors.intValue();
    }

    @ManagedAttribute("")
    public int getHandlings()
    {
        return (int)_handleStats.getTotal();
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

    @Override
    protected void process(StatisticsRequest request, Response wrappedResponse, Callback callback, Request.Processor nextProcessor) throws Exception
    {
        request.process(wrappedResponse, callback, nextProcessor);
    }

    protected class StatisticsRequest extends Request.Wrapper
    {
        private final LongAdder _bytesRead = new LongAdder();
        private final LongAdder _bytesWritten = new LongAdder();
        private long _processStartNanoTime;

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
                case "o.e.j.s.h.StatsHandler.bytesRead" -> _bytesRead.longValue();
                case "o.e.j.s.h.StatsHandler.bytesWritten" -> _bytesWritten.longValue();
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
            return NanoTime.since(_processStartNanoTime);
        }

        public void process(Response response, Callback callback, Request.Processor nextProcessor) throws Exception
        {
            _processStartNanoTime = NanoTime.now();
            _processStats.increment();
            _requestStats.increment();

            String id = getConnectionMetaData().getId();
            if (_connectionStats.add(id))
            {
                // TODO test this with localconnector endpoint that has multiple requests per connection.
                getConnectionMetaData().getConnection().addEventListener(new Connection.Listener()
                {
                    @Override
                    public void onClosed(Connection connection)
                    {
                        _connectionStats.remove(id);
                    }
                });
            }

            addHttpStreamWrapper(s -> new HttpStream.Wrapper(s)
            {
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

                    _bytesWritten.add(BufferUtil.length(content));

                    super.send(request, response, last, content, callback);
                }

                @Override
                public Content.Chunk read()
                {
                    Content.Chunk chunk =  super.read();
                    if (chunk != null)
                        _bytesRead.add(chunk.remaining());
                    return chunk;
                }

                @Override
                public void succeeded()
                {
                    _requestStats.decrement();
                    _requestTimeStats.record(NanoTime.since(getNanoTimeStamp()));
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    _requestStats.decrement();
                    _requestTimeStats.record(NanoTime.since(getNanoTimeStamp()));
                    super.failed(x);
                }
            });

            try
            {
                nextProcessor.process(this, response, callback);
            }
            catch (Throwable t)
            {
                _processingErrors.increment();
                throw t;
            }
            finally
            {
                _processStats.decrement();
                _processTimeStats.record(NanoTime.since(_processStartNanoTime));
            }
        }
    }

    static class MinimumDataRateHandler extends StatisticsHandler
    {
        private final long _minimumReadRate;
        private final long _minimumWriteRate;

        public MinimumDataRateHandler(long minimumReadRate, long minimumWriteRate)
        {
            _minimumReadRate = minimumReadRate;
            _minimumWriteRate = minimumWriteRate;
        }

        @Override
        protected MinimumDataRateRequest wrap(Request request)
        {
            return new MinimumDataRateRequest(request);
        }

        @Override
        protected MinimumDataRateResponse wrap(StatisticsRequest request, Response response)
        {
            return new MinimumDataRateResponse(request, response);
        }

        private class MinimumDataRateRequest extends StatisticsRequest
        {
            private Content.Chunk.Error _errorContent;

            private MinimumDataRateRequest(Request request)
            {
                super(request);
            }

            @Override
            public void demand(Runnable demandCallback)
            {
                if (_minimumReadRate > 0)
                {
                    Long rr = (Long)getAttribute("o.e.j.s.h.StatsHandler.dataReadRate");
                    if (rr < _minimumReadRate)
                    {
                        // TODO should this be a QuietException to reduce log verbosity from bad clients?
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

        private class MinimumDataRateResponse extends Response.Wrapper
        {
            private MinimumDataRateResponse(Request request, Response wrapped)
            {
                super(request, wrapped);
            }

            @Override
            public void write(boolean last, ByteBuffer content, Callback callback)
            {
                if (_minimumWriteRate > 0)
                {
                    MinimumDataRateRequest request = (MinimumDataRateRequest)getRequest();
                    if (((Long)request.getAttribute("o.e.j.s.h.StatsHandler.bytesWritten")) > 0L)
                    {
                        Long wr = (Long)request.getAttribute("o.e.j.s.h.StatsHandler.dataWriteRate");
                        if (wr < _minimumWriteRate)
                        {
                            TimeoutException cause = new TimeoutException("write rate is too low: " + wr);
                            request._errorContent = Content.Chunk.from(cause);
                            callback.failed(cause);
                            return;
                        }
                    }
                }
                super.write(last, content, callback);
            }
        }
    }
}
