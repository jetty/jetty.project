// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class StatisticsHandler extends HandlerWrapper
{
    private transient final AtomicLong _statsStartedAt = new AtomicLong();
    private transient final AtomicInteger _requests = new AtomicInteger();
    private transient final AtomicInteger _resumedRequests = new AtomicInteger();
    private transient final AtomicInteger _expiredRequests = new AtomicInteger();
    private transient final AtomicLong _requestMinTime = new AtomicLong();
    private transient final AtomicLong _requestMaxTime = new AtomicLong();
    private transient final AtomicLong _requestTotalTime = new AtomicLong();
    private transient final AtomicLong _suspendMinTime = new AtomicLong();
    private transient final AtomicLong _suspendTotalTime = new AtomicLong();
    private transient final AtomicInteger _requestsActive = new AtomicInteger();
    private transient final AtomicInteger _requestsMaxActive = new AtomicInteger();
    private transient final AtomicInteger _responses1xx = new AtomicInteger();
    private transient final AtomicInteger _responses2xx = new AtomicInteger();
    private transient final AtomicInteger _responses3xx = new AtomicInteger();
    private transient final AtomicInteger _responses4xx = new AtomicInteger();
    private transient final AtomicInteger _responses5xx = new AtomicInteger();
    private transient final AtomicLong _responsesTotalBytes = new AtomicLong();

    /**
     * Resets the current request statistics.
     */
    public void statsReset()
    {
        _statsStartedAt.set(System.currentTimeMillis());
        _requests.set(0);
        _resumedRequests.set(0);
        _expiredRequests.set(0);
        _requestMinTime.set(Long.MAX_VALUE);
        _requestMaxTime.set(0L);
        _requestTotalTime.set(0L);
        _suspendMinTime.set(Long.MAX_VALUE);
        _suspendTotalTime.set(0L);
        _requestsActive.set(0);
        _requestsMaxActive.set(0);
        _responses1xx.set(0);
        _responses2xx.set(0);
        _responses3xx.set(0);
        _responses4xx.set(0);
        _responses5xx.set(0);
        _responsesTotalBytes.set(0L);
    }

    private void updateMax(AtomicInteger valueHolder, int value)
    {
        int oldValue = valueHolder.get();
        while (value > oldValue)
        {
            if (valueHolder.compareAndSet(oldValue, value))
                break;
            oldValue = valueHolder.get();
        }
    }

    private void updateMax(AtomicLong valueHolder, long value)
    {
        long oldValue = valueHolder.get();
        while (value > oldValue)
        {
            if (valueHolder.compareAndSet(oldValue, value))
                break;
            oldValue = valueHolder.get();
        }
    }

    private void updateMin(AtomicLong valueHolder, long value)
    {
        long oldValue = valueHolder.get();
        while (value < oldValue)
        {
            if (valueHolder.compareAndSet(oldValue, value))
                break;
            oldValue = valueHolder.get();
        }
    }

    public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
    {
        _requests.incrementAndGet();

        int activeRequests = _requestsActive.incrementAndGet();
        updateMax(_requestsMaxActive, activeRequests);

        // The order of the ifs is important, as a continuation can be resumed and expired
        // We test first if it's expired, and then if it's resumed
        AsyncContinuation continuation = request.getAsyncContinuation();
        if (continuation.isExpired())
        {
            _expiredRequests.incrementAndGet();
        }
        else if (continuation.isResumed())
        {
            _resumedRequests.incrementAndGet();

            long initialTime = request.getTimeStamp();
            long suspendTime = System.currentTimeMillis() - initialTime;
            updateMin(_suspendMinTime, suspendTime);
            _suspendTotalTime.addAndGet(suspendTime);
        }

        try
        {
            super.handle(path, request, httpRequest, httpResponse);
        }
        finally
        {
            _requestsActive.decrementAndGet();

            if (!continuation.isSuspended())
            {
                updateResponse(request);
            }
        }
    }

    private void updateResponse(Request request)
    {
        long elapsed = System.currentTimeMillis() - request.getTimeStamp();

        updateMin(_requestMinTime, elapsed);
        updateMax(_requestMaxTime, elapsed);
        _requestTotalTime.addAndGet(elapsed);

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

    protected void doStart() throws Exception
    {
        super.doStart();
        statsReset();
    }

    /**
     * @return the number of requests handled by this handler
     * since {@link #statsReset()} was last called, including
     * resumed requests
     * @see #getRequestsResumed()
     */
    public int getRequests()
    {
        return _requests.get();
    }

    /**
     * @return the number of requests currently active.
     * since {@link #statsReset()} was last called.
     */
    public int getRequestsActive()
    {
        return _requestsActive.get();
    }

    /**
     * @return the maximum number of active requests
     * since {@link #statsReset()} was last called.
     */
    public int getRequestsActiveMax()
    {
        return _requestsMaxActive.get();
    }

    /**
     * @return the number of requests that have been resumed
     * @see #getRequestsExpired()
     */
    public int getRequestsResumed()
    {
        return _resumedRequests.get();
    }

    /**
     * @return the number of requests that expired while suspended.
     * @see #getRequestsResumed()
     */
    public int getRequestsExpired()
    {
        return _expiredRequests.get();
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
     * @return the minimum time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     */
    public long getRequestTimeMin()
    {
        return _requestMinTime.get();
    }

    /**
     * @return the maximum time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     */
    public long getRequestTimeMax()
    {
        return _requestMaxTime.get();
    }

    /**
     * @return the total time (in milliseconds) of requests handling
     * since {@link #statsReset()} was last called.
     */
    public long getRequestTimeTotal()
    {
        return _requestTotalTime.get();
    }

    /**
     * @return the average time (in milliseconds) of request handling
     * since {@link #statsReset()} was last called.
     * @see #getRequestTimeTotal()
     * @see #getRequests()
     */
    public long getRequestTimeAverage()
    {
        int requests = getRequests();
        return requests == 0 ? 0 : getRequestTimeTotal() / requests;
    }

    /**
     * @return the total bytes of content sent in responses
     */
    public long getResponsesBytesTotal()
    {
        return _responsesTotalBytes.get();
    }

    /**
     * @return the minimum time (in milliseconds) of request suspension
     * since {@link #statsReset()} was last called.
     */
    public long getSuspendedTimeMin()
    {
        return _suspendMinTime.get();
    }

    /**
     * @return the total time (in milliseconds) of request suspension
     * since {@link #statsReset()} was last called.
     */
    public long getSuspendedTimeTotal()
    {
        return _suspendTotalTime.get();
    }
}
