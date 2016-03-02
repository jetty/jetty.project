//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.continuation;

import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;

/**
 * Continuation.
 * <p> 
 * A continuation is a mechanism by which a HTTP Request can be suspended and
 * restarted after a timeout or an asynchronous event has occurred.
 * <p>
 * The continuation mechanism is a portable mechanism that will work 
 * asynchronously without additional configuration of all jetty-7, 
 * jetty-8 and Servlet 3.0 containers.   With the addition of 
 * the {@link ContinuationFilter}, the mechanism will also work
 * asynchronously on jetty-6 and non-asynchronously on any 
 * servlet 2.5 container.
 * <p>
 * The Continuation API is a simplification of the richer async API
 * provided by the servlet-3.0 and an enhancement of the continuation
 * API that was introduced with jetty-6. 
 * </p>
 * <h1>Continuation Usage</h1>
 * <p>
 * A continuation object is obtained for a request by calling the 
 * factory method {@link ContinuationSupport#getContinuation(ServletRequest)}.
 * The continuation type returned will depend on the servlet container
 * being used.
 * </p> 
 * <p>
 * There are two distinct style of operation of the continuation API.
 * </p>
 * <h1>Suspend/Resume Usage</h1> 
 * <p>The suspend/resume style is used when a servlet and/or
 * filter is used to generate the response after a asynchronous wait that is
 * terminated by an asynchronous handler.
 * </p>
 * <pre>
 * <b>Filter/Servlet:</b>
 *   // if we need to get asynchronous results
 *   Object results = request.getAttribute("results);
 *   if (results==null)
 *   {
 *     Continuation continuation = ContinuationSupport.getContinuation(request);
 *     continuation.suspend();
 *     myAsyncHandler.register(continuation);
 *     return; // or continuation.undispatch();
 *   }
 * 
 * async wait ...
 * 
 * <b>Async Handler:</b>
 *   // when the waited for event happens
 *   continuation.setAttribute("results",event);
 *   continuation.resume();
 *   
 * <b>Filter/Servlet:</b>
 *   // when the request is redispatched 
 *   if (results==null)
 *   {
 *     ... // see above
 *   }
 *   else
 *   {
 *     response.getOutputStream().write(process(results));
 *   }
 * </pre> 
 * <h1>Suspend/Complete Usage</h1> 
 * <p>
 * The suspend/complete style is used when an asynchronous handler is used to 
 * generate the response:
 * </p>
 * <pre>
 * <b>Filter/Servlet:</b>
 *   // when we want to enter asynchronous mode
 *   Continuation continuation = ContinuationSupport.getContinuation(request);
 *   continuation.suspend(response); // response may be wrapped
 *   myAsyncHandler.register(continuation);
 *   return; // or continuation.undispatch();
 *
 * <b>Wrapping Filter:</b>
 *   // any filter that had wrapped the response should be implemented like:
 *   try
 *   {
 *     chain.doFilter(request,wrappedResponse);
 *   }
 *   finally
 *   {
 *     if (!continuation.isResponseWrapped())
 *       wrappedResponse.finish()
 *     else
 *       continuation.addContinuationListener(myCompleteListener)
 *   }
 *
 * async wait ...
 *
 * <b>Async Handler:</b>
 *   // when the async event happens
 *   continuation.getServletResponse().getOutputStream().write(process(event));
 *   continuation.complete()
 * </pre>
 * 
 * <h1>Continuation Timeout</h1>
 * <p>
 * If a continuation is suspended, but neither {@link #complete()} or {@link #resume()} is
 * called during the period set by {@link #setTimeout(long)}, then the continuation will
 * expire and {@link #isExpired()} will return true. 
 * </p>
 * <p>
 * When a continuation expires, the {@link ContinuationListener#onTimeout(Continuation)}
 * method is called on any {@link ContinuationListener} that has been registered via the
 * {@link #addContinuationListener(ContinuationListener)} method. The onTimeout handlers 
 * may write a response and call {@link #complete()}. If {@link #complete()} is not called, 
 * then the container will redispatch the request as if {@link #resume()} had been called,
 * except that {@link #isExpired()} will be true and {@link #isResumed()} will be false.
 * </p>
 * 
 * @see ContinuationSupport
 * @see ContinuationListener
 * 
 */
public interface Continuation
{
    public final static String ATTRIBUTE = "org.eclipse.jetty.continuation";

    /* ------------------------------------------------------------ */
    /**
     * Set the continuation timeout.
     * 
     * @param timeoutMs
     *            The time in milliseconds to wait before expiring this
     *            continuation after a call to {@link #suspend()} or {@link #suspend(ServletResponse)}.
     *            A timeout of &lt;=0 means the continuation will never expire.
     */
    void setTimeout(long timeoutMs);

    /* ------------------------------------------------------------ */
    /**
     * Suspend the processing of the request and associated
     * {@link ServletResponse}.
     * 
     * <p>
     * After this method has been called, the lifecycle of the request will be
     * extended beyond the return to the container from the
     * {@link javax.servlet.Servlet#service(ServletRequest, ServletResponse)} method and
     * {@link javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)}
     * calls. When a suspended request is returned to the container after
     * a dispatch, then the container will not commit the associated response
     * (unless an exception other than {@link ContinuationThrowable} is thrown).
     * </p>
     * 
     * <p>
     * When the thread calling the filter chain and/or servlet has returned to
     * the container with a suspended request, the thread is freed for other
     * tasks and the request is held until either:
     * <ul>
     * <li>a call to {@link #resume()}.</li>
     * <li>a call to {@link #complete()}.</li>
     * <li>the timeout expires.</li>
     * </ul>
     * <p>
     * Typically suspend with no arguments is uses when a call to {@link #resume()}
     * is expected. If a call to {@link #complete()} is expected, then the 
     * {@link #suspend(ServletResponse)} method should be used instead of this method.
     * </p>
     * 
     * @exception IllegalStateException
     *                If the request cannot be suspended
     */
    void suspend();
    
    
    /* ------------------------------------------------------------ */
    /**
     * Suspend the processing of the request and associated
     * {@link ServletResponse}.
     * 
     * <p>
     * After this method has been called, the lifecycle of the request will be
     * extended beyond the return to the container from the
     * {@link javax.servlet.Servlet#service(ServletRequest, ServletResponse)} method and
     * {@link javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)}
     * calls. When a suspended request is returned to the container after
     * a dispatch, then the container will not commit the associated response
     * (unless an exception other than {@link ContinuationThrowable} is thrown).
     * </p>
     * <p>
     * When the thread calling the filter chain and/or servlet has returned to
     * the container with a suspended request, the thread is freed for other
     * tasks and the request is held until either:
     * <ul>
     * <li>a call to {@link #resume()}.</li>
     * <li>a call to {@link #complete()}.</li>
     * <li>the timeout expires.</li>
     * </ul>
     * <p>
     * Typically suspend with a response argument is uses when a call to {@link #complete()}
     * is expected. If a call to {@link #resume()} is expected, then the 
     * {@link #suspend()} method should be used instead of this method.
     * </p>
     * <p>
     * Filters that may wrap the response object should check {@link #isResponseWrapped()}
     * to decide if they should destroy/finish the wrapper. If {@link #isResponseWrapped()}
     * returns true, then the wrapped request has been passed to the asynchronous
     * handler and the wrapper should not be destroyed/finished until after a call to 
     * {@link #complete()} (potentially using a {@link ContinuationListener#onComplete(Continuation)}
     * listener).
     * 
     * @param response The response to return via a call to {@link #getServletResponse()}
     * @exception IllegalStateException
     *                If the request cannot be suspended
     */
    void suspend(ServletResponse response);

    /* ------------------------------------------------------------ */
    /**
     * Resume a suspended request.
     * 
     * <p>
     * This method can be called by any thread that has been passed a reference
     * to a continuation. When called the request is redispatched to the
     * normal filter chain and servlet processing with {@link #isInitial()} false.
     * </p>
     * <p>
     * If resume is called before a suspended request is returned to the
     * container (ie the thread that called {@link #suspend()} is still
     * within the filter chain and/or servlet service method), then the resume
     * does not take effect until the call to the filter chain and/or servlet
     * returns to the container. In this case both {@link #isSuspended()} and
     * {@link #isResumed()} return true. Multiple calls to resume are ignored.
     * </p>
     * <p>
     * Typically resume() is used after a call to {@link #suspend()} with
     * no arguments. The dispatch after a resume call will use the original
     * request and response objects, even if {@link #suspend(ServletResponse)} 
     * had been passed a wrapped response.
     * </p>
     * 
     * @see #suspend()
     * @exception IllegalStateException if the request is not suspended.
     * 
     */
    void resume();

    /* ------------------------------------------------------------ */
    /**
     * Complete a suspended request.
     * 
     * <p>
     * This method can be called by any thread that has been passed a reference
     * to a suspended request. When a request is completed, the associated
     * response object committed and flushed. The request is not redispatched.
     * </p>
     * 
     * <p>
     * If complete is called before a suspended request is returned to the
     * container (ie the thread that called {@link #suspend()} is still
     * within the filter chain and/or servlet service method), then the complete
     * does not take effect until the call to the filter chain and/or servlet
     * returns to the container. In this case both {@link #isSuspended()} and
     * {@link #isResumed()} return true.
     * </p>
     * 
     * <p>
     * Typically resume() is used after a call to {@link #suspend(ServletResponse)} with
     * a possibly wrapped response. The async handler should use the response
     * provided by {@link #getServletResponse()} to write the response before
     * calling {@link #complete()}. If the request was suspended with a 
     * call to {@link #suspend()} then no response object will be available via
     * {@link #getServletResponse()}.
     * </p>
     * 
     * <p>
     * Once complete has been called and any thread calling the filter chain
     * and/or servlet chain has returned to the container, the request lifecycle
     * is complete. The container is able to recycle request objects, so it is
     * not valid hold a request or continuation reference after the end of the 
     * life cycle.
     * 
     * @see #suspend()
     * @exception IllegalStateException
     *                if the request is not suspended.
     * 
     */
    void complete();

    /* ------------------------------------------------------------ */
    /**
     * @return true after {@link #suspend()} has been called and before the
     *         request has been redispatched due to being resumed, completed or
     *         timed out.
     */
    boolean isSuspended();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the request has been redispatched by a call to
     *         {@link #resume()}. Returns false after any subsequent call to
     *         suspend
     */
    boolean isResumed();

    /* ------------------------------------------------------------ */
    /**
     * @return true after a request has been redispatched as the result of a
     *         timeout. Returns false after any subsequent call to suspend.
     */
    boolean isExpired();

    /* ------------------------------------------------------------ */
    /**
     * @return true while the request is within the initial dispatch to the
     *         filter chain and/or servlet. Will return false once the calling
     *         thread has returned to the container after suspend has been
     *         called and during any subsequent redispatch.
     */
    boolean isInitial();

    /* ------------------------------------------------------------ */
    /**
     * Is the suspended response wrapped.
     * <p>
     * Filters that wrap the response object should check this method to 
     * determine if they should destroy/finish the wrapped response. If 
     * the request was suspended with a call to {@link #suspend(ServletResponse)}
     * that passed the wrapped response, then the filter should register
     * a {@link ContinuationListener} to destroy/finish the wrapped response
     * during a call to {@link ContinuationListener#onComplete(Continuation)}.
     * @return True if {@link #suspend(ServletResponse)} has been passed a
     * {@link ServletResponseWrapper} instance.
     */
    boolean isResponseWrapped();


    /* ------------------------------------------------------------ */
    /**
     * Get the suspended response.
     * @return the {@link ServletResponse} passed to {@link #suspend(ServletResponse)}.
     */
    ServletResponse getServletResponse();
    
    /* ------------------------------------------------------------ */
    /** 
     * Add a ContinuationListener.
     * 
     * @param listener the listener
     */
    void addContinuationListener(ContinuationListener listener);
    
    /* ------------------------------------------------------------ */
    /** Set a request attribute.
     * This method is a convenience method to call the {@link ServletRequest#setAttribute(String, Object)}
     * method on the associated request object.
     * This is a thread safe call and may be called by any thread.
     * @param name the attribute name
     * @param attribute the attribute value
     */
    public void setAttribute(String name, Object attribute);
    
    /* ------------------------------------------------------------ */
    /** Get a request attribute.
     * This method is a convenience method to call the {@link ServletRequest#getAttribute(String)}
     * method on the associated request object.
     * This is a thread safe call and may be called by any thread.
     * @param name the attribute name
     * @return the attribute value
     */
    public Object getAttribute(String name);
    
    /* ------------------------------------------------------------ */
    /** Remove a request attribute.
     * This method is a convenience method to call the {@link ServletRequest#removeAttribute(String)}
     * method on the associated request object.
     * This is a thread safe call and may be called by any thread.
     * @param name the attribute name
     */
    public void removeAttribute(String name);
    
    /* ------------------------------------------------------------ */
    /**
     * Undispatch the request.
     * <p>
     * This method can be called on a suspended continuation in order
     * to exit the dispatch to the filter/servlet by throwing a {@link ContinuationThrowable}
     * which is caught either by the container or the {@link ContinuationFilter}.
     * This is an alternative to simply returning from the dispatch in the case
     * where filters in the filter chain may not be prepared to handle a suspended
     * request.
     * </p>
     * This method should only be used as a last resort and a normal return is a prefereable
     * solution if filters can be updated to handle that case.
     * 
     * @throws ContinuationThrowable thrown if the request is suspended. The instance of the 
     * exception may be reused on subsequent calls, so the stack frame may not be accurate.
     */
    public void undispatch() throws ContinuationThrowable;
}
