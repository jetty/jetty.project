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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Quality of Service Filter.
 * 
 * This filter limits the number of active requests to the number set by the "maxRequests" init parameter (default 10).
 * If more requests are received, they are suspended and placed on priority queues.  Priorities are determined by 
 * the {@link #getPriority(ServletRequest)} method and are a value between 0 and the value given by the "maxPriority" 
 * init parameter (default 10), with higher values having higher priority.
 * </p><p>
 * This filter is ideal to prevent wasting threads waiting for slow/limited 
 * resources such as a JDBC connection pool.  It avoids the situation where all of a 
 * containers thread pool may be consumed blocking on such a slow resource.
 * By limiting the number of active threads, a smaller thread pool may be used as 
 * the threads are not wasted waiting.  Thus more memory may be available for use by 
 * the active threads.
 * </p><p>
 * Furthermore, this filter uses a priority when resuming waiting requests. So that if
 * a container is under load, and there are many requests waiting for resources,
 * the {@link #getPriority(ServletRequest)} method is used, so that more important 
 * requests are serviced first.     For example, this filter could be deployed with a 
 * maxRequest limit slightly smaller than the containers thread pool and a high priority 
 * allocated to admin users.  Thus regardless of load, admin users would always be
 * able to access the web application.
 * </p><p>
 * The maxRequest limit is policed by a {@link Semaphore} and the filter will wait a short while attempting to acquire
 * the semaphore. This wait is controlled by the "waitMs" init parameter and allows the expense of a suspend to be
 * avoided if the semaphore is shortly available.  If the semaphore cannot be obtained, the request will be suspended
 * for the default suspend period of the container or the valued set as the "suspendMs" init parameter.
 * </p><p>
 * If the "managedAttr" init parameter is set to true, then this servlet is set as a {@link ServletContext} attribute with the 
 * filter name as the attribute name.  This allows context external mechanism (eg JMX via {@link ContextHandler#MANAGED_ATTRIBUTES}) to
 * manage the configuration of the filter.
 * </p>
 * 
 *
 */
public class QoSFilter implements Filter
{
    final static int __DEFAULT_MAX_PRIORITY=10;
    final static int __DEFAULT_PASSES=10;
    final static int __DEFAULT_WAIT_MS=50;
    final static long __DEFAULT_TIMEOUT_MS = -1;
    
    final static String MANAGED_ATTR_INIT_PARAM="managedAttr";
    final static String MAX_REQUESTS_INIT_PARAM="maxRequests";
    final static String MAX_PRIORITY_INIT_PARAM="maxPriority";
    final static String MAX_WAIT_INIT_PARAM="waitMs";
    final static String SUSPEND_INIT_PARAM="suspendMs";
    
    ServletContext _context;

    protected long _waitMs;
    protected long _suspendMs;
    protected int _maxRequests;
    
    private Semaphore _passes;
    private Queue<Continuation>[] _queue;
    private ContinuationListener[] _listener;
    private String _suspended="QoSFilter@"+this.hashCode();
    
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    public void init(FilterConfig filterConfig) 
    {
        _context=filterConfig.getServletContext();

        int max_priority=__DEFAULT_MAX_PRIORITY;
        if (filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM)!=null)
            max_priority=Integer.parseInt(filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM));
        _queue=new Queue[max_priority+1];
        _listener = new ContinuationListener[max_priority + 1];
        for (int p=0;p<_queue.length;p++)
        {
            _queue[p]=new ConcurrentLinkedQueue<Continuation>();

            final int priority=p;
            _listener[p] = new ContinuationListener()
            {
                public void onComplete(Continuation continuation)
                {}

                public void onTimeout(Continuation continuation)
                {
                    _queue[priority].remove(continuation);
                }
            };
        }
        
        int maxRequests=__DEFAULT_PASSES;
        if (filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM)!=null)
            maxRequests=Integer.parseInt(filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM));
        _passes=new Semaphore(maxRequests,true);
        _maxRequests = maxRequests;
        
        long wait = __DEFAULT_WAIT_MS;
        if (filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM)!=null)
            wait=Integer.parseInt(filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM));
        _waitMs=wait;
        
        long suspend = __DEFAULT_TIMEOUT_MS;
        if (filterConfig.getInitParameter(SUSPEND_INIT_PARAM)!=null)
            suspend=Integer.parseInt(filterConfig.getInitParameter(SUSPEND_INIT_PARAM));
        _suspendMs=suspend;

        if (_context!=null && Boolean.parseBoolean(filterConfig.getInitParameter(MANAGED_ATTR_INIT_PARAM)))
            _context.setAttribute(filterConfig.getFilterName(),this);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
    throws IOException, ServletException
    {
        boolean accepted=false;
        try
        {
            if (request.getAttribute(_suspended)==null)
            {
                accepted=_passes.tryAcquire(_waitMs,TimeUnit.MILLISECONDS);
                if (accepted)
                {
                    request.setAttribute(_suspended,Boolean.FALSE);
                }
                else
                {
                    request.setAttribute(_suspended,Boolean.TRUE);
                    int priority = getPriority(request);
                    Continuation continuation = ContinuationSupport.getContinuation(request);
                    if (_suspendMs>0)
                        continuation.setTimeout(_suspendMs);
                    continuation.suspend();
                    continuation.addContinuationListener(_listener[priority]);
                    _queue[priority].add(continuation);
                    return;
                }
            }
            else
            {
                Boolean suspended=(Boolean)request.getAttribute(_suspended);
                
                if (suspended.booleanValue())
                {
                    request.setAttribute(_suspended,Boolean.FALSE);
                    if (request.getAttribute("javax.servlet.resumed")==Boolean.TRUE)
                    {
                        _passes.acquire();
                        accepted=true;
                    }
                    else 
                    {
                        // Timeout! try 1 more time.
                        accepted = _passes.tryAcquire(_waitMs,TimeUnit.MILLISECONDS);
                    }
                }
                else
                {
                    // pass through resume of previously accepted request
                    _passes.acquire();
                    accepted = true;
                }
            }

            if (accepted)
            {
                chain.doFilter(request,response);
            }
            else
            {
                ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        }
        catch(InterruptedException e)
        {
            _context.log("QoS",e);
            ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        finally
        {
            if (accepted)
            {
                for (int p=_queue.length;p-->0;)
                {
                    Continuation continutaion=_queue[p].poll();
                    if (continutaion!=null && continutaion.isSuspended())
                    {
                        continutaion.resume();
                        break;
                    }
                }
                _passes.release();
            }
        }
    }

    /** 
     * Get the request Priority.
     * <p> The default implementation assigns the following priorities:<ul>
     * <li> 2 - for a authenticated request
     * <li> 1 - for a request with valid /non new session 
     * <li> 0 - for all other requests.
     * </ul>
     * This method may be specialised to provide application specific priorities.
     * 
     * @param request
     * @return the request priority
     */
    protected int getPriority(ServletRequest request)
    {
        HttpServletRequest baseRequest = (HttpServletRequest)request;
        if (baseRequest.getUserPrincipal() != null )
            return 2;
        else 
        {
            HttpSession session = baseRequest.getSession(false);
            if (session!=null && !session.isNew()) 
                return 1;
            else
                return 0;
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#destroy()
     */
    public void destroy(){}

    /* ------------------------------------------------------------ */
    /** 
     * Get the (short) amount of time (in milliseconds) that the filter would wait
     * for the semaphore to become available before suspending a request.
     * 
     * @return wait time (in milliseconds)
     */
    public long getWaitMs()
    {
        return _waitMs;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the (short) amount of time (in milliseconds) that the filter would wait
     * for the semaphore to become available before suspending a request.
     * 
     * @param value wait time (in milliseconds)
     */
    public void setWaitMs(long value)
    {
        _waitMs = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the amount of time (in milliseconds) that the filter would suspend
     * a request for while waiting for the semaphore to become available.
     * 
     * @return suspend time (in milliseconds)
     */
    public long getSuspendMs()
    {
        return _suspendMs;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the amount of time (in milliseconds) that the filter would suspend
     * a request for while waiting for the semaphore to become available.
     * 
     * @param value suspend time (in milliseconds)
     */
    public void setSuspendMs(long value)
    {
        _suspendMs = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the maximum number of requests allowed to be processed
     * at the same time.
     * 
     * @return maximum number of requests
     */
    public int getMaxRequests()
    {
        return _maxRequests;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the maximum number of requests allowed to be processed
     * at the same time.
     * 
     * @param value the number of requests
     */
    public void setMaxRequests(int value)
    {
        _passes = new Semaphore((value-_maxRequests+_passes.availablePermits()), true);
        _maxRequests = value;
    }

}
