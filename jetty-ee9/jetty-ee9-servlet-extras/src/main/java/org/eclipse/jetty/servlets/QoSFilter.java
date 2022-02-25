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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quality of Service Filter.
 * <p>
 * This filter limits the number of active requests to the number set by the "maxRequests" init parameter (default 10).
 * If more requests are received, they are suspended and placed on priority queues.  Priorities are determined by
 * the {@link #getPriority(ServletRequest)} method and are a value between 0 and the value given by the "maxPriority"
 * init parameter (default 10), with higher values having higher priority.
 * <p>
 * This filter is ideal to prevent wasting threads waiting for slow/limited
 * resources such as a JDBC connection pool.  It avoids the situation where all of a
 * containers thread pool may be consumed blocking on such a slow resource.
 * By limiting the number of active threads, a smaller thread pool may be used as
 * the threads are not wasted waiting.  Thus more memory may be available for use by
 * the active threads.
 * <p>
 * Furthermore, this filter uses a priority when resuming waiting requests. So that if
 * a container is under load, and there are many requests waiting for resources,
 * the {@link #getPriority(ServletRequest)} method is used, so that more important
 * requests are serviced first.     For example, this filter could be deployed with a
 * maxRequest limit slightly smaller than the containers thread pool and a high priority
 * allocated to admin users.  Thus regardless of load, admin users would always be
 * able to access the web application.
 * <p>
 * The maxRequest limit is policed by a {@link Semaphore} and the filter will wait a short while attempting to acquire
 * the semaphore. This wait is controlled by the "waitMs" init parameter and allows the expense of a suspend to be
 * avoided if the semaphore is shortly available.  If the semaphore cannot be obtained, the request will be suspended
 * for the default suspend period of the container or the valued set as the "suspendMs" init parameter.
 * <p>
 * If the "managedAttr" init parameter is set to true, then this servlet is set as a {@link ServletContext} attribute with the
 * filter name as the attribute name.  This allows context external mechanism (eg JMX via {@link ContextHandler#MANAGED_ATTRIBUTES}) to
 * manage the configuration of the filter.
 */
@ManagedObject("Quality of Service Filter")
public class QoSFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(QoSFilter.class);

    static final int __DEFAULT_MAX_PRIORITY = 10;
    static final int __DEFAULT_PASSES = 10;
    static final int __DEFAULT_WAIT_MS = 50;
    static final long __DEFAULT_TIMEOUT_MS = -1;

    static final String MANAGED_ATTR_INIT_PARAM = "managedAttr";
    static final String MAX_REQUESTS_INIT_PARAM = "maxRequests";
    static final String MAX_PRIORITY_INIT_PARAM = "maxPriority";
    static final String MAX_WAIT_INIT_PARAM = "waitMs";
    static final String SUSPEND_INIT_PARAM = "suspendMs";

    private final String _suspended = "QoSFilter@" + Integer.toHexString(hashCode()) + ".SUSPENDED";
    private final String _resumed = "QoSFilter@" + Integer.toHexString(hashCode()) + ".RESUMED";
    private long _waitMs;
    private long _suspendMs;
    private int _maxRequests;
    private Semaphore _passes;
    private Queue<AsyncContext>[] _queues;
    private AsyncListener[] _listeners;

    @Override
    public void init(FilterConfig filterConfig)
    {
        int maxPriority = __DEFAULT_MAX_PRIORITY;
        if (filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM) != null)
            maxPriority = Integer.parseInt(filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM));
        _queues = new Queue[maxPriority + 1];
        _listeners = new AsyncListener[_queues.length];
        for (int p = 0; p < _queues.length; ++p)
        {
            _queues[p] = new ConcurrentLinkedQueue<>();
            _listeners[p] = new QoSAsyncListener(p);
        }

        int maxRequests = __DEFAULT_PASSES;
        if (filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM) != null)
            maxRequests = Integer.parseInt(filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM));
        _passes = new Semaphore(maxRequests, true);
        _maxRequests = maxRequests;

        long wait = __DEFAULT_WAIT_MS;
        if (filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM) != null)
            wait = Integer.parseInt(filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM));
        _waitMs = wait;

        long suspend = __DEFAULT_TIMEOUT_MS;
        if (filterConfig.getInitParameter(SUSPEND_INIT_PARAM) != null)
            suspend = Integer.parseInt(filterConfig.getInitParameter(SUSPEND_INIT_PARAM));
        _suspendMs = suspend;

        ServletContext context = filterConfig.getServletContext();
        if (context != null && Boolean.parseBoolean(filterConfig.getInitParameter(MANAGED_ATTR_INIT_PARAM)))
            context.setAttribute(filterConfig.getFilterName(), this);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        boolean accepted = false;
        try
        {
            Boolean suspended = (Boolean)request.getAttribute(_suspended);
            if (suspended == null)
            {
                accepted = _passes.tryAcquire(getWaitMs(), TimeUnit.MILLISECONDS);
                if (accepted)
                {
                    request.setAttribute(_suspended, Boolean.FALSE);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Accepted {}", request);
                }
                else
                {
                    request.setAttribute(_suspended, Boolean.TRUE);
                    int priority = getPriority(request);
                    AsyncContext asyncContext = request.startAsync();
                    long suspendMs = getSuspendMs();
                    if (suspendMs > 0)
                        asyncContext.setTimeout(suspendMs);
                    asyncContext.addListener(_listeners[priority]);
                    _queues[priority].add(asyncContext);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Suspended {}", request);
                    return;
                }
            }
            else
            {
                if (suspended)
                {
                    request.setAttribute(_suspended, Boolean.FALSE);
                    Boolean resumed = (Boolean)request.getAttribute(_resumed);
                    if (Boolean.TRUE.equals(resumed))
                    {
                        _passes.acquire();
                        accepted = true;
                        if (LOG.isDebugEnabled())
                            LOG.debug("Resumed {}", request);
                    }
                    else
                    {
                        // Timeout! try 1 more time.
                        accepted = _passes.tryAcquire(getWaitMs(), TimeUnit.MILLISECONDS);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Timeout {}", request);
                    }
                }
                else
                {
                    // Pass through resume of previously accepted request.
                    _passes.acquire();
                    accepted = true;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Passthrough {}", request);
                }
            }

            if (accepted)
            {
                chain.doFilter(request, response);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Rejected {}", request);
                ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        }
        catch (InterruptedException e)
        {
            ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        finally
        {
            if (accepted)
            {
                _passes.release();

                for (int p = _queues.length - 1; p >= 0; --p)
                {
                    AsyncContext asyncContext = _queues[p].poll();
                    if (asyncContext != null)
                    {
                        ServletRequest candidate = asyncContext.getRequest();
                        Boolean suspended = (Boolean)candidate.getAttribute(_suspended);
                        if (Boolean.TRUE.equals(suspended))
                        {
                            try
                            {  
                                candidate.setAttribute(_resumed, Boolean.TRUE);
                                asyncContext.dispatch();
                                break;
                            }
                            catch (IllegalStateException x)
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("dispatch failed", x);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the request priority.
     * <p>
     * The default implementation assigns the following priorities:
     * <ul>
     * <li> 2 - for an authenticated request
     * <li> 1 - for a request with valid / non new session
     * <li> 0 - for all other requests.
     * </ul>
     * This method may be overridden to provide application specific priorities.
     *
     * @param request the incoming request
     * @return the computed request priority
     */
    protected int getPriority(ServletRequest request)
    {
        HttpServletRequest baseRequest = (HttpServletRequest)request;
        if (baseRequest.getUserPrincipal() != null)
        {
            return 2;
        }
        else
        {
            HttpSession session = baseRequest.getSession(false);
            if (session != null && !session.isNew())
                return 1;
            else
                return 0;
        }
    }

    @Override
    public void destroy()
    {
    }

    /**
     * Get the (short) amount of time (in milliseconds) that the filter would wait
     * for the semaphore to become available before suspending a request.
     *
     * @return wait time (in milliseconds)
     */
    @ManagedAttribute("(short) amount of time filter will wait before suspending request (in ms)")
    public long getWaitMs()
    {
        return _waitMs;
    }

    /**
     * Set the (short) amount of time (in milliseconds) that the filter would wait
     * for the semaphore to become available before suspending a request.
     *
     * @param value wait time (in milliseconds)
     * @deprecated use init-param waitMs instead
     */
    @Deprecated
    public void setWaitMs(long value)
    {
        LOG.warn("Setter ignored: use waitMs init-param for QoSFilter");
    }

    /**
     * Get the amount of time (in milliseconds) that the filter would suspend
     * a request for while waiting for the semaphore to become available.
     *
     * @return suspend time (in milliseconds)
     */
    @ManagedAttribute("amount of time filter will suspend a request for while waiting for the semaphore to become available (in ms)")
    public long getSuspendMs()
    {
        return _suspendMs;
    }

    /**
     * Set the amount of time (in milliseconds) that the filter would suspend
     * a request for while waiting for the semaphore to become available.
     *
     * @param value suspend time (in milliseconds)
     * @deprecated use init-param suspendMs instead
     */
    @Deprecated
    public void setSuspendMs(long value)
    {
        LOG.warn("Setter ignored: use suspendMs init-param for QoSFilter");
    }

    /**
     * Get the maximum number of requests allowed to be processed
     * at the same time.
     *
     * @return maximum number of requests
     */
    @ManagedAttribute("maximum number of requests to allow processing of at the same time")
    public int getMaxRequests()
    {
        return _maxRequests;
    }

    /**
     * Set the maximum number of requests allowed to be processed
     * at the same time.
     *
     * @param value the number of requests
     * @deprecated use init-param maxRequests instead
     */
    @Deprecated
    public void setMaxRequests(int value)
    {
        LOG.warn("Setter ignored: use maxRequests init-param for QoSFilter instead");
    }

    private class QoSAsyncListener implements AsyncListener
    {
        private final int priority;

        public QoSAsyncListener(int priority)
        {
            this.priority = priority;
        }

        @Override
        public void onStartAsync(AsyncEvent event)
        {
        }

        @Override
        public void onComplete(AsyncEvent event)
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            // Remove before it's redispatched, so it won't be
            // redispatched again at the end of the filtering.
            AsyncContext asyncContext = event.getAsyncContext();
            _queues[priority].remove(asyncContext);
            ((HttpServletResponse)event.getSuppliedResponse()).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            asyncContext.complete();
        }

        @Override
        public void onError(AsyncEvent event)
        {
        }
    }
}
