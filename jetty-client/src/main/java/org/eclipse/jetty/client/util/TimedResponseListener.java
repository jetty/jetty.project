//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Schedulable;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * Implementation of {@link Response.Listener} that allows to specify a timeout for asynchronous
 * operations.
 * <p />
 * {@link TimedResponseListener} may be used to decorate a delegate {@link Response.CompleteListener}
 * provided by the application. Events are forwarded by {@link TimedResponseListener} to the delegate
 * listener.
 * Alternatively, {@link TimedResponseListener} may be subclassed to override callbacks that are
 * interesting to the application, typically {@link #onComplete(Result)}.
 * <p />
 * If the timeout specified at the constructor elapses, the request is {@link Request#abort(Throwable) aborted}
 * with a {@link TimeoutException}.
 * <p />
 * Typical usage is:
 * <pre>
 * Request request = httpClient.newRequest(...)...;
 * TimedResponseListener listener = new TimedResponseListener(5, TimeUnit.SECONDS, request, new Response.CompleteListener()
 * {
 *     public void onComplete(Result result)
 *     {
 *         // Invoked when request/response completes or when timeout elapses
 *
 *         // Your logic here
 *     }
 * });
 * request.send(listener); // Asynchronous send
 * </pre>
 */
public class TimedResponseListener implements Response.Listener, Schedulable, Runnable
{
    private static final Logger LOG = Log.getLogger(TimedResponseListener.class);

    private final AtomicReference<Scheduler.Task> task = new AtomicReference<>();
    private final long timeout;
    private final TimeUnit unit;
    private final Request request;
    private final Response.CompleteListener delegate;

    public TimedResponseListener(long timeout, TimeUnit unit, Request request)
    {
        this(timeout, unit, request, new Empty());
    }

    public TimedResponseListener(long timeout, TimeUnit unit, Request request, Response.CompleteListener delegate)
    {
        this.timeout = timeout;
        this.unit = unit;
        this.request = request;
        this.delegate = delegate;
    }

    @Override
    public void onBegin(Response response)
    {
        if (delegate instanceof Response.BeginListener)
            ((Response.BeginListener)delegate).onBegin(response);
    }

    @Override
    public boolean onHeader(Response response, HttpField field)
    {
        if (delegate instanceof Response.HeaderListener)
            return ((Response.HeaderListener)delegate).onHeader(response, field);
        return true;
    }

    @Override
    public void onHeaders(Response response)
    {
        if (delegate instanceof Response.HeadersListener)
            ((Response.HeadersListener)delegate).onHeaders(response);
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        if (delegate instanceof Response.ContentListener)
            ((Response.ContentListener)delegate).onContent(response, content);
    }

    @Override
    public void onSuccess(Response response)
    {
        if (delegate instanceof Response.SuccessListener)
            ((Response.SuccessListener)delegate).onSuccess(response);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        if (delegate instanceof Response.FailureListener)
            ((Response.FailureListener)delegate).onFailure(response, failure);
    }

    @Override
    public void onComplete(Result result)
    {
        delegate.onComplete(result);
    }

    public boolean schedule(Scheduler scheduler)
    {
        Scheduler.Task task = this.task.get();
        if (task != null)
            return false;

        task = scheduler.schedule(this, timeout, unit);
        if (this.task.compareAndSet(null, task))
        {
            LOG.debug("Scheduled timeout task {} in {} ms", task, unit.toMillis(timeout));
            return true;
        }
        else
        {
            task.cancel();
            return false;
        }
    }

    @Override
    public void run()
    {
        request.abort(new TimeoutException("Total timeout elapsed"));
    }

    public boolean cancel()
    {
        Scheduler.Task task = this.task.get();
        if (task == null)
            return false;
        boolean result = task.cancel();
        LOG.debug("Cancelled timeout task {}", task);
        return result;
    }
}
