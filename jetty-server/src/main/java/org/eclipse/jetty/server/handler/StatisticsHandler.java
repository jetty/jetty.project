//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends HandlerWrapper
{
    private final AtomicLong _statsStartedAt = new AtomicLong();
    
    private final CounterStatistic _requestStats = new CounterStatistic();
    private final SampleStatistic _requestTimeStats = new SampleStatistic();
    private final CounterStatistic _dispatchedStats = new CounterStatistic();
    private final SampleStatistic _dispatchedTimeStats = new SampleStatistic();
    private final CounterStatistic _suspendStats = new CounterStatistic();

    private final AtomicInteger _resumes = new AtomicInteger();
    private final AtomicInteger _expires = new AtomicInteger();
    
    private final AtomicInteger _responses1xx = new AtomicInteger();
    private final AtomicInteger _responses2xx = new AtomicInteger();
    private final AtomicInteger _responses3xx = new AtomicInteger();
    private final AtomicInteger _responses4xx = new AtomicInteger();
    private final AtomicInteger _responses5xx = new AtomicInteger();
    private final AtomicLong _responsesTotalBytes = new AtomicLong();

    private final ContinuationListener _onCompletion = new ContinuationListener()
    {
        public void onComplete(Continuation continuation)
        {
            final Request request = ((AsyncContinuation)continuation).getBaseRequest();
            final long elapsed = System.currentTimeMillis()-request.getTimeStamp();
            
            _requestStats.decrement();
            _requestTimeStats.set(elapsed);
            
            updateResponse(request);
            
            if (!continuation.isResumed())
                _suspendStats.decrement();
        }

        public void onTimeout(Continuation continuation)
        {
            _expires.incrementAndGet();
        }
    };
    
    /**
     * Resets the current request statistics.
     */
    public void statsReset()
    {
        _statsStartedAt.set(System.currentTimeMillis());
        
        _requestStats.reset();
        _requestTimeStats.reset();
        _dispatchedStats.reset();
        _dispatchedTimeStats.reset();
        _suspendStats.reset();

        _resumes.set(0);
        _expires.set(0);
        _responses1xx.set(0);
        _responses2xx.set(0);
        _responses3xx.set(0);
        _responses4xx.set(0);
        _responses5xx.set(0);
        _responsesTotalBytes.set(0L);
    }

    @Override
    public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
    {
        _dispatchedStats.increment();

        final long start;
        AsyncContinuation continuation = request.getAsyncContinuation();
        if (continuation.isInitial())
        {
            // new request
            _requestStats.increment();
            start = request.getTimeStamp();
        }
        else
        {
            // resumed request
            start = System.currentTimeMillis();
            _suspendStats.decrement();
            if (continuation.isResumed())
                _resumes.incrementAndGet();
        }

        try
        {
            super.handle(path, request, httpRequest, httpResponse);
        }
        finally
        {
            final long now = System.currentTimeMillis();
            final long dispatched=now-start;
            
            _dispatchedStats.decrement();
            _dispatchedTimeStats.set(dispatched);
            
            if (continuation.isSuspended())
            {
                if (continuation.isInitial())
                    continuation.addContinuationListener(_onCompletion);
                _suspendStats.increment();
            }
            else if (continuation.isInitial())
            {
                _requestStats.decrement();
                _requestTimeStats.set(dispatched);
                updateResponse(request);
            }
            // else onCompletion will handle it.
        }
    }

    private void updateResponse(Request request)
    {
        Response response = request.getResponse();
        switch (response.getStatus() / 100)
        {
            case 1:
                _responses1xx.incrementAndGet();
                break;
            case 2:
                _responses2xx.incrementAndGet();
                break;
            case 3:
                _responses3xx.incrementAndGet();
                break;
            case 4:
                _responses4xx.incrementAndGet();
                break;
            case 5:
                _responses5xx.incrementAndGet();
                break;
            default:
                break;
        }
        _responsesTotalBytes.addAndGet(response.getContentCount());
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        statsReset();
    }

    /**
     * @return the number of requests handled by this handler
     * since {@link #statsReset()} was last called, excluding
     * active requests
     * @see #getResumes()
     */
    public int getRequests()
    {
        return (int)_requestStats.getTotal();
    }

    /**
     * @return the number of requests currently active.
     * since {@link #statsReset()} was last called.
     */
    public int getRequestsActive()
    {
        return (int)_requestStats.getCurrent();
    }

    /**
     * @return the maximum number of active requests
     * since {@link #statsReset()} was last called.
     */
    public int getRequestsActiveMax()
    {
        return (int)_requestStats.getMax();
    }

    /**
     * @return the maximum time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     */
    public long getRequestTimeMax()
    {
        return _requestTimeStats.getMax();
    }

    /**
     * @return the total time (in milliseconds) of requests handling
     * since {@link #statsReset()} was last called.
     */
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
    public double getRequestTimeStdDev()
    {
        return _requestTimeStats.getStdDev();
    }

    /**
     * @return the number of dispatches seen by this handler
     * since {@link #statsReset()} was last called, excluding
     * active dispatches
     */
    public int getDispatched()
    {
        return (int)_dispatchedStats.getTotal();
    }

    /**
     * @return the number of dispatches currently in this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     */
    public int getDispatchedActive()
    {
        return (int)_dispatchedStats.getCurrent();
    }

    /**
     * @return the max number of dispatches currently in this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     */
    public int getDispatchedActiveMax()
    {
        return (int)_dispatchedStats.getMax();
    }

    /**
     * @return the maximum time (in milliseconds) of request dispatch
     * since {@link #statsReset()} was last called.
     */
    public long getDispatchedTimeMax()
    {
        return _dispatchedTimeStats.getMax();
    }
    
    /**
     * @return the total time (in milliseconds) of requests handling
     * since {@link #statsReset()} was last called.
     */
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
    public double getDispatchedTimeStdDev()
    {
        return _dispatchedTimeStats.getStdDev();
    }
    
    /**
     * @return the number of requests handled by this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     * @see #getResumes()
     */
    public int getSuspends()
    {
        return (int)_suspendStats.getTotal();
    }

    /**
     * @return the number of requests currently suspended.
     * since {@link #statsReset()} was last called.
     */
    public int getSuspendsActive()
    {
        return (int)_suspendStats.getCurrent();
    }

    /**
     * @return the maximum number of current suspended requests
     * since {@link #statsReset()} was last called.
     */
    public int getSuspendsActiveMax()
    {
        return (int)_suspendStats.getMax();
    }
    
    /**
     * @return the number of requests that have been resumed
     * @see #getExpires()
     */
    public int getResumes()
    {
        return _resumes.get();
    }

    /**
     * @return the number of requests that expired while suspended.
     * @see #getResumes()
     */
    public int getExpires()
    {
        return _expires.get();
    }

    /**
     * @return the number of responses with a 1xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    public int getResponses1xx()
    {
        return _responses1xx.get();
    }

    /**
     * @return the number of responses with a 2xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    public int getResponses2xx()
    {
        return _responses2xx.get();
    }

    /**
     * @return the number of responses with a 3xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    public int getResponses3xx()
    {
        return _responses3xx.get();
    }

    /**
     * @return the number of responses with a 4xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    public int getResponses4xx()
    {
        return _responses4xx.get();
    }

    /**
     * @return the number of responses with a 5xx status returned by this context
     * since {@link #statsReset()} was last called.
     */
    public int getResponses5xx()
    {
        return _responses5xx.get();
    }

    /**
     * @return the milliseconds since the statistics were started with {@link #statsReset()}.
     */
    public long getStatsOnMs()
    {
        return System.currentTimeMillis() - _statsStartedAt.get();
    }
    
    /**
     * @return the total bytes of content sent in responses
     */
    public long getResponsesBytesTotal()
    {
        return _responsesTotalBytes.get();
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


        sb.append("Total requests suspended: ").append(getSuspends()).append("<br />\n");
        sb.append("Total requests expired: ").append(getExpires()).append("<br />\n");
        sb.append("Total requests resumed: ").append(getResumes()).append("<br />\n");
        
        sb.append("<h2>Responses:</h2>\n");
        sb.append("1xx responses: ").append(getResponses1xx()).append("<br />\n");
        sb.append("2xx responses: ").append(getResponses2xx()).append("<br />\n");
        sb.append("3xx responses: ").append(getResponses3xx()).append("<br />\n");
        sb.append("4xx responses: ").append(getResponses4xx()).append("<br />\n");
        sb.append("5xx responses: ").append(getResponses5xx()).append("<br />\n");
        sb.append("Bytes sent total: ").append(getResponsesBytesTotal()).append("<br />\n");

        return sb.toString();

    }
}
