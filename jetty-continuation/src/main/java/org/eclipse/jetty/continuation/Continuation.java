// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.continuation;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/* ------------------------------------------------------------ */
/**
 * Continuation.
 * 
 * A continuation is a mechanism by which a HTTP Request can be suspended and
 * restarted after a timeout or an asynchronous event has occurred.
 * <p>
 * Continuations will use the asynchronous APIs if they used by a 
 * webapp deployed in Jetty or a Servlet 3.0 container.   For other 
 * containers, the {@link ContinuationFilter} may be used to 
 * simulate asynchronous features.
 * </p>
 * 
 * @see ContinuationSupport
 * 
 */
public interface Continuation
{
    public final static String ATTRIBUTE = "org.eclipse.jetty.continuation";

    /**
     * Set the continuation timeout
     * 
     * @param timeoutMs
     *            The time in milliseconds to wait before expiring this
     *            continuation.
     */
    void setTimeout(long timeoutMs);

    /**
     * Suspend the processing of the request and associated
     * {@link ServletResponse}.
     * 
     * <p>
     * After this method has been called, the lifecycle of the request will be
     * extended beyond the return to the container from the
     * {@link Servlet#service(ServletRequest, ServletResponse)} method and
     * {@link Filter#doFilter(ServletRequest, ServletResponse, FilterChain)}
     * calls. If a request is suspended, then the container will not commit the
     * associated response when the call to the filter chain and/or servlet
     * service method returns to the container. Any exceptions thrown to the
     * container by a filter chain and/or servlet for a suspended requests are
     * silently ignored.
     * </p>
     * 
     * <p>
     * When the thread calling the filter chain and/or servlet has returned to
     * the container with a suspended request, the thread is freed for other
     * tasks and the request is held pending either:
     * <ul>
     * <li>a call to {@link ServletRequest#resume()}.</li>
     * <li>a call to {@link ServletRequest#complete()}.</li>
     * <li>the passed or default timeout expires.</li>
     * <li>there is IO activity on the connection that received the request,
     * such as the close of the connection or the receipt of a pipelined
     * request.
     * </ul>
     * <p>
     * After any of the events listed above, the suspended request will be
     * redispatched via the filter and servlet processing.
     * </p>
     * 
     * <p>
     * Suspend may only be called by a thread that is within the service calling
     * stack of
     * {@link Filter#doFilter(ServletRequest, ServletResponse, FilterChain)}
     * and/or {@link Servlet#service(ServletRequest, ServletResponse)}. A
     * request that has been dispatched for error handling may not be suspended.
     * </p>
     * 
     * @see {@link #resume()}
     * @see {@link #complete()}
     * 
     * @exception IllegalStateException
     *                If the calling thread is not within the calling stack of
     *                {@link Filter#doFilter(ServletRequest, ServletResponse, FilterChain)}
     *                and/or
     *                {@link Servlet#service(ServletRequest, ServletResponse)}
     *                or if the request has been dispatched for error handling.
     */
    void suspend();
    
    void suspend(ServletResponse response);

    /**
     * Resume a suspended request.
     * 
     * <p>
     * This method can be called by any thread that has been passed a reference
     * to a suspended request. When called the request is redispatched to the
     * normal filter chain and servlet processing.
     * </p>
     * 
     * <p>
     * If resume is called before a suspended request is returned to the
     * container (ie the thread that called {@link #suspend(long)} is still
     * within the filter chain and/or servlet service method), then the resume
     * does not take effect until the call to the filter chain and/or servlet
     * returns to the container. In this case both {@link #isSuspended()} and
     * {@link isResumed()} return true.
     * </p>
     * 
     * <p>
     * Multiple calls to resume are ignored
     * </p>
     * 
     * @see {@link #suspend()}
     * @exception IllegalStateException
     *                if the request is not suspended.
     * 
     */
    void resume();

    /**
     * Complete a suspended request.
     * 
     * <p>
     * This method can be called by any thread that has been passed a reference
     * to a suspended request. When a request is completed, the associated
     * response object commited and flushed. The request is not redispatched.
     * </p>
     * 
     * <p>
     * If complete is called before a suspended request is returned to the
     * container (ie the thread that called {@link #suspend(long)} is still
     * within the filter chain and/or servlet service method), then the complete
     * does not take effect until the call to the filter chain and/or servlet
     * returns to the container. In this case both {@link #isSuspended()} and
     * {@link isResumed()} return true.
     * </p>
     * 
     * <p>
     * Once complete has been called and any thread calling the filter chain
     * and/or servlet chain has returned to the container, the request lifecycle
     * is complete. The container is able to recycle request objects, so it is
     * not valid hold a request reference after the end of the life cycle or to
     * call any request methods.
     * 
     * @see {@link #suspend()}
     * @exception IllegalStateException
     *                if the request is not suspended.
     * 
     */
    void complete();

    /**
     * @return true after {@link #suspend(long)} has been called and before the
     *         request has been redispatched due to being resumed, completed or
     *         timed out.
     */
    boolean isSuspended();

    /**
     * @return true if the request has been redispatched by a call to
     *         {@link #resume()}. Returns false after any subsequent call to
     *         suspend
     */
    boolean isResumed();

    /**
     * @return true after a request has been redispatched as the result of a
     *         timeout. Returns false after any subsequent call to suspend.
     */
    boolean isExpired();

    /**
     * @return true while the request is within the initial dispatch to the
     *         filter chain and/or servlet. Will return false once the calling
     *         thread has returned to the container after suspend has been
     *         called and during any subsequent redispatch.
     */
    boolean isInitial();
    
    /**
     * @return True if {@link #keepWrappers()} has been called.
     */
    boolean isResponseWrapped();

    ServletResponse getServletResponse();
    
    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addContinuationListener(ContinuationListener listener);
    
    public void removeAttribute(String name);
    public void setAttribute(String name, Object attribute);
    public Object getAttribute(String name);
}
