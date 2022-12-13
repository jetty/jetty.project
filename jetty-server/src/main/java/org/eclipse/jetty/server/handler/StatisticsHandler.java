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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AsyncContextEvent;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("Request Statistics Gathering")
public class StatisticsHandler extends HandlerWrapper implements Graceful
{
    private static final Logger LOG = LoggerFactory.getLogger(StatisticsHandler.class);
    private final AtomicLong _statsStartedAt = new AtomicLong();
    private final Shutdown _shutdown;

    private final CounterStatistic _requestStats = new CounterStatistic();
    private final SampleStatistic _requestTimeStats = new SampleStatistic();
    private final CounterStatistic _dispatchedStats = new CounterStatistic();
    private final SampleStatistic _dispatchedTimeStats = new SampleStatistic();
    private final CounterStatistic _asyncWaitStats = new CounterStatistic();

    private final LongAdder _asyncDispatches = new LongAdder();
    private final LongAdder _expires = new LongAdder();
    private final LongAdder _errors = new LongAdder();

    private final LongAdder _responsesThrown = new LongAdder();
    private final LongAdder _responses1xx = new LongAdder();
    private final LongAdder _responses2xx = new LongAdder();
    private final LongAdder _responses3xx = new LongAdder();
    private final LongAdder _responses4xx = new LongAdder();
    private final LongAdder _responses5xx = new LongAdder();
    private final LongAdder _responsesTotalBytes = new LongAdder();

    private boolean _gracefulShutdownWaitsForRequests = true;

    private final AsyncListener _onCompletion = new AsyncListener()
    {
        @Override
        public void onStartAsync(AsyncEvent event)
        {
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onTimeout(AsyncEvent event)
        {
            _expires.increment();
        }

        @Override
        public void onError(AsyncEvent event)
        {
            _errors.increment();
        }

        @Override
        public void onComplete(AsyncEvent event)
        {
            Request request = ((AsyncContextEvent)event).getHttpChannelState().getBaseRequest();
            long elapsed = System.currentTimeMillis() - request.getTimeStamp();
            _requestStats.decrement();
            _requestTimeStats.record(elapsed);
            updateResponse(request, false);
            _asyncWaitStats.decrement();

            if (_shutdown.isShutdown())
                _shutdown.check();
        }
    };

    public StatisticsHandler()
    {
        _shutdown = new Shutdown(this)
        {
            @Override
            public boolean isShutdownDone()
            {
                if (_gracefulShutdownWaitsForRequests)
                    return _requestStats.getCurrent() == 0;
                else
                    return _dispatchedStats.getCurrent() == 0;
            }
        };
    }

    /**
     * Resets the current request statistics.
     */
    @ManagedOperation(value = "resets statistics", impact = "ACTION")
    public void statsReset()
    {
        _statsStartedAt.set(System.currentTimeMillis());

        _requestStats.reset();
        _requestTimeStats.reset();
        _dispatchedStats.reset();
        _dispatchedTimeStats.reset();
        _asyncWaitStats.reset();

        _asyncDispatches.reset();
        _expires.reset();
        _responses1xx.reset();
        _responses2xx.reset();
        _responses3xx.reset();
        _responses4xx.reset();
        _responses5xx.reset();
        _responsesTotalBytes.reset();
    }

    @Override
    public void handle(String path, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handler handler = getHandler();
        if (handler == null || !isStarted() || isShutdown())
        {
            if (!baseRequest.getResponse().isCommitted())
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE_503);
            return;
        }

        _dispatchedStats.increment();

        final long start;
        HttpChannelState state = baseRequest.getHttpChannelState();
        if (state.isInitial())
        {
            // new request
            _requestStats.increment();
            start = baseRequest.getTimeStamp();
        }
        else
        {
            // resumed request
            start = System.currentTimeMillis();
            _asyncDispatches.increment();
        }

        boolean thrownError = false;
        try
        {
            handler.handle(path, baseRequest, request, response);
        }
        catch (Throwable t)
        {
            thrownError = true;
            throw t;
        }
        finally
        {
            final long now = System.currentTimeMillis();
            final long dispatched = now - start;

            _dispatchedStats.decrement();
            _dispatchedTimeStats.record(dispatched);

            if (state.isInitial())
            {
                if (state.isAsyncStarted())
                {
                    state.addListener(_onCompletion);
                    _asyncWaitStats.increment();
                }
                else
                {
                    _requestStats.decrement();
                    _requestTimeStats.record(dispatched);
                    updateResponse(baseRequest, thrownError);
                }
            }

            if (_shutdown.isShutdown())
                _shutdown.check();
        }
    }

    protected void updateResponse(Request request, boolean thrownError)
    {
        Response response = request.getResponse();
        if (thrownError)
        {
            _responsesThrown.increment();
        }
        else if (request.isHandled())
        {
            switch (response.getStatus() / 100)
            {
                case 1:
                    _responses1xx.increment();
                    break;
                case 2:
                    _responses2xx.increment();
                    break;
                case 3:
                    _responses3xx.increment();
                    break;
                case 4:
                    _responses4xx.increment();
                    break;
                case 5:
                    _responses5xx.increment();
                    break;
                default:
                    break;
            }
        }
        else
        {
            // will fall through to not found handler
            _responses4xx.increment();
        }

        _responsesTotalBytes.add(response.getContentCount());
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getHandler() == null)
            throw new IllegalStateException("StatisticsHandler has no Wrapped Handler");
        _shutdown.cancel();
        super.doStart();
        statsReset();
    }

    @Override
    protected void doStop() throws Exception
    {
        _shutdown.cancel();
        super.doStop();
    }

    /**
     * Set whether the graceful shutdown should wait for all requests to complete including
     * async requests which are not currently dispatched, or whether it should only wait for all the
     * actively dispatched requests to complete.
     * @param gracefulShutdownWaitsForRequests true to wait for async requests on graceful shutdown.
     */
    public void setGracefulShutdownWaitsForRequests(boolean gracefulShutdownWaitsForRequests)
    {
        _gracefulShutdownWaitsForRequests = gracefulShutdownWaitsForRequests;
    }

    /**
     * @return whether the graceful shutdown will wait for all requests to complete including
     * async requests which are not currently dispatched, or whether it will only wait for all the
     * actively dispatched requests to complete.
     * @see #getAsyncDispatches()
     */
    @ManagedAttribute("if graceful shutdown will wait for all requests")
    public boolean getGracefulShutdownWaitsForRequests()
    {
        return _gracefulShutdownWaitsForRequests;
    }

    /**
     * @return the number of requests handled by this handler
     * since {@link #statsReset()} was last called, excluding
     * active requests
     * @see #getAsyncDispatches()
     */
    @ManagedAttribute("number of requests")
    public int getRequests()
    {
        return (int)_requestStats.getTotal();
    }

    /**
     * @return the number of requests currently active.
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests currently active")
    public int getRequestsActive()
    {
        return (int)_requestStats.getCurrent();
    }

    /**
     * @return the maximum number of active requests
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("maximum number of active requests")
    public int getRequestsActiveMax()
    {
        return (int)_requestStats.getMax();
    }

    /**
     * @return the maximum time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("maximum time spend handling requests (in ms)")
    public long getRequestTimeMax()
    {
        return _requestTimeStats.getMax();
    }

    /**
     * @return the total time (in milliseconds) of requests handling
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("total time spend in all request handling (in ms)")
    public long getRequestTimeTotal()
    {
        return _requestTimeStats.getTotal();
    }

    /**
     * @return the mean time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     * @see #getRequestTimeTotal()
     * @see #getRequests()
     */
    @ManagedAttribute("mean time spent handling requests (in ms)")
    public double getRequestTimeMean()
    {
        return _requestTimeStats.getMean();
    }

    /**
     * @return the standard deviation of time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     * @see #getRequestTimeTotal()
     * @see #getRequests()
     */
    @ManagedAttribute("standard deviation for request handling (in ms)")
    public double getRequestTimeStdDev()
    {
        return _requestTimeStats.getStdDev();
    }

    /**
     * @return the number of dispatches seen by this handler
     * since {@link #statsReset()} was last called, excluding
     * active dispatches
     */
    @ManagedAttribute("number of dispatches")
    public int getDispatched()
    {
        return (int)_dispatchedStats.getTotal();
    }

    /**
     * @return the number of dispatches currently in this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     */
    @ManagedAttribute("number of dispatches currently active")
    public int getDispatchedActive()
    {
        return (int)_dispatchedStats.getCurrent();
    }

    /**
     * @return the max number of dispatches currently in this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     */
    @ManagedAttribute("maximum number of active dispatches being handled")
    public int getDispatchedActiveMax()
    {
        return (int)_dispatchedStats.getMax();
    }

    /**
     * @return the maximum time (in milliseconds) of request dispatch
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("maximum time spend in dispatch handling")
    public long getDispatchedTimeMax()
    {
        return _dispatchedTimeStats.getMax();
    }

    /**
     * @return the total time (in milliseconds) of requests handling
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("total time spent in dispatch handling (in ms)")
    public long getDispatchedTimeTotal()
    {
        return _dispatchedTimeStats.getTotal();
    }

    /**
     * @return the mean time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     * @see #getRequestTimeTotal()
     * @see #getRequests()
     */
    @ManagedAttribute("mean time spent in dispatch handling (in ms)")
    public double getDispatchedTimeMean()
    {
        return _dispatchedTimeStats.getMean();
    }

    /**
     * @return the standard deviation of time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     * @see #getRequestTimeTotal()
     * @see #getRequests()
     */
    @ManagedAttribute("standard deviation for dispatch handling (in ms)")
    public double getDispatchedTimeStdDev()
    {
        return _dispatchedTimeStats.getStdDev();
    }

    /**
     * @return the number of requests handled by this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     * @see #getAsyncDispatches()
     */
    @ManagedAttribute("total number of async requests")
    public int getAsyncRequests()
    {
        return (int)_asyncWaitStats.getTotal();
    }

    /**
     * @return the number of requests currently suspended.
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("currently waiting async requests")
    public int getAsyncRequestsWaiting()
    {
        return (int)_asyncWaitStats.getCurrent();
    }

    /**
     * @return the maximum number of current suspended requests
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("maximum number of waiting async requests")
    public int getAsyncRequestsWaitingMax()
    {
        return (int)_asyncWaitStats.getMax();
    }

    /**
     * @return the number of requests that have been asynchronously dispatched
     */
    @ManagedAttribute("number of requested that have been asynchronously dispatched")
    public int getAsyncDispatches()
    {
        return _asyncDispatches.intValue();
    }

    /**
     * @return the number of requests that expired while suspended.
     * @see #getAsyncDispatches()
     */
    @ManagedAttribute("number of async requests requests that have expired")
    public int getExpires()
    {
        return _expires.intValue();
    }

    /**
     * @return the number of async errors that occurred.
     * @see #getAsyncDispatches()
     */
    @ManagedAttribute("number of async errors that occurred")
    public int getErrors()
    {
        return _errors.intValue();
    }

    /**
     * @return the number of responses with a 1xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests with 1xx response status")
    public int getResponses1xx()
    {
        return _responses1xx.intValue();
    }

    /**
     * @return the number of responses with a 2xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests with 2xx response status")
    public int getResponses2xx()
    {
        return _responses2xx.intValue();
    }

    /**
     * @return the number of responses with a 3xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests with 3xx response status")
    public int getResponses3xx()
    {
        return _responses3xx.intValue();
    }

    /**
     * @return the number of responses with a 4xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests with 4xx response status")
    public int getResponses4xx()
    {
        return _responses4xx.intValue();
    }

    /**
     * @return the number of responses with a 5xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    @ManagedAttribute("number of requests with 5xx response status")
    public int getResponses5xx()
    {
        return _responses5xx.intValue();
    }

    /**
     * @return the number of requests that threw an exception during handling
     * since {@link #statsReset()} was last called. These may have resulted in
     * some error responses which were unrecorded by the {@link StatisticsHandler}.
     */
    @ManagedAttribute("number of requests that threw an exception during handling")
    public int getResponsesThrown()
    {
        return _responsesThrown.intValue();
    }


    /**
     * @return the milliseconds since the statistics were started with {@link #statsReset()}.
     */
    @ManagedAttribute("time in milliseconds stats have been collected for")
    public long getStatsOnMs()
    {
        return System.currentTimeMillis() - _statsStartedAt.get();
    }

    /**
     * @return the total bytes of content sent in responses
     */
    @ManagedAttribute("total number of bytes across all responses")
    public long getResponsesBytesTotal()
    {
        return _responsesTotalBytes.longValue();
    }

    public String toStatsHTML()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("<h1>Statistics:</h1>\n");
        sb.append("Statistics gathering started ").append(getStatsOnMs()).append("ms ago").append("<br />\n");

        sb.append("<h2>Requests:</h2>\n");
        sb.append("Total requests: ").append(getRequests()).append("<br />\n");
        sb.append("Active requests: ").append(getRequestsActive()).append("<br />\n");
        sb.append("Max active requests: ").append(getRequestsActiveMax()).append("<br />\n");
        sb.append("Total requests time: ").append(getRequestTimeTotal()).append("<br />\n");
        sb.append("Mean request time: ").append(getRequestTimeMean()).append("<br />\n");
        sb.append("Max request time: ").append(getRequestTimeMax()).append("<br />\n");
        sb.append("Request time standard deviation: ").append(getRequestTimeStdDev()).append("<br />\n");

        sb.append("<h2>Dispatches:</h2>\n");
        sb.append("Total dispatched: ").append(getDispatched()).append("<br />\n");
        sb.append("Active dispatched: ").append(getDispatchedActive()).append("<br />\n");
        sb.append("Max active dispatched: ").append(getDispatchedActiveMax()).append("<br />\n");
        sb.append("Total dispatched time: ").append(getDispatchedTimeTotal()).append("<br />\n");
        sb.append("Mean dispatched time: ").append(getDispatchedTimeMean()).append("<br />\n");
        sb.append("Max dispatched time: ").append(getDispatchedTimeMax()).append("<br />\n");
        sb.append("Dispatched time standard deviation: ").append(getDispatchedTimeStdDev()).append("<br />\n");

        sb.append("Total requests suspended: ").append(getAsyncRequests()).append("<br />\n");
        sb.append("Total requests expired: ").append(getExpires()).append("<br />\n");
        sb.append("Total requests resumed: ").append(getAsyncDispatches()).append("<br />\n");

        sb.append("<h2>Responses:</h2>\n");
        sb.append("1xx responses: ").append(getResponses1xx()).append("<br />\n");
        sb.append("2xx responses: ").append(getResponses2xx()).append("<br />\n");
        sb.append("3xx responses: ").append(getResponses3xx()).append("<br />\n");
        sb.append("4xx responses: ").append(getResponses4xx()).append("<br />\n");
        sb.append("5xx responses: ").append(getResponses5xx()).append("<br />\n");
        sb.append("responses thrown: ").append(getResponsesThrown()).append("<br />\n");
        sb.append("Bytes sent total: ").append(getResponsesBytesTotal()).append("<br />\n");

        return sb.toString();
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return _shutdown.shutdown();
    }

    @Override
    public boolean isShutdown()
    {
        return _shutdown.isShutdown();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,r=%d,d=%d}", getClass().getSimpleName(), hashCode(), getState(), _requestStats.getCurrent(), _dispatchedStats.getCurrent());
    }

}
