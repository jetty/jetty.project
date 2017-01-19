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

package org.eclipse.jetty.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class TimeoutCompleteListener implements Response.CompleteListener, Runnable
{
    private static final Logger LOG = Log.getLogger(TimeoutCompleteListener.class);

    private final AtomicReference<Scheduler.Task> task = new AtomicReference<>();
    private final Request request;

    public TimeoutCompleteListener(Request request)
    {
        this.request = request;
    }

    @Override
    public void onComplete(Result result)
    {
        cancel();
    }

    public boolean schedule(Scheduler scheduler)
    {
        long timeout = request.getTimeout();
        Scheduler.Task task = scheduler.schedule(this, timeout, TimeUnit.MILLISECONDS);
        Scheduler.Task existing = this.task.getAndSet(task);
        if (existing != null)
        {
            existing.cancel();
            cancel();
            throw new IllegalStateException();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Scheduled timeout task {} in {} ms for {}", task, timeout, request);
        return true;
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Executing timeout task {} for {}", task, request);
        request.abort(new TimeoutException("Total timeout " + request.getTimeout() + " ms elapsed"));
    }

    public void cancel()
    {
        Scheduler.Task task = this.task.getAndSet(null);
        if (task != null)
        {
            boolean cancelled = task.cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled (successfully: {}) timeout task {}", cancelled, task);
        }
    }
}
