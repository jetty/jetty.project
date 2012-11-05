//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Schedulable;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class TimedResponseListener implements Response.Listener, Schedulable, Runnable
{
    private static final Logger LOG = Log.getLogger(TimedResponseListener.class);

    private final AtomicReference<Scheduler.Task> task = new AtomicReference<>();
    private final long timeout;
    private final TimeUnit unit;
    private final Request request;
    private final Response.Listener delegate;

    public TimedResponseListener(long timeout, TimeUnit unit, Request request)
    {
        this(timeout, unit, request, new Empty());
    }

    public TimedResponseListener(long timeout, TimeUnit unit, Request request, Response.Listener delegate)
    {
        this.timeout = timeout;
        this.unit = unit;
        this.request = request;
        this.delegate = delegate;
    }

    @Override
    public void onBegin(Response response)
    {
        delegate.onBegin(response);
    }

    @Override
    public void onHeaders(Response response)
    {
        delegate.onHeaders(response);
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        delegate.onContent(response, content);
    }

    @Override
    public void onSuccess(Response response)
    {
        delegate.onSuccess(response);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        delegate.onFailure(response, failure);
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
        request.abort("Total timeout elapsed");
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
