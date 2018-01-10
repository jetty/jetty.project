//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class TimeoutCompleteListener extends CyclicTimeout implements Response.CompleteListener
{
    private static final Logger LOG = Log.getLogger(TimeoutCompleteListener.class);

    private final AtomicReference<Request> request = new AtomicReference<>();

    public TimeoutCompleteListener(Scheduler scheduler)
    {
        super(scheduler);
    }

    @Override
    public void onTimeoutExpired()
    {
        Request request = this.request.getAndSet(null);
        if (LOG.isDebugEnabled())
            LOG.debug("Total timeout {} ms elapsed for {}", request.getTimeout(), request);
        if (request != null)
            request.abort(new TimeoutException("Total timeout " + request.getTimeout() + " ms elapsed"));
    }

    @Override
    public void onComplete(Result result)
    {
        Request request = this.request.getAndSet(null);
        if (request != null)
        {
            boolean cancelled = cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled ({}) timeout for {}", cancelled, request);
        }
    }

    void schedule(HttpRequest request, long timeoutAt)
    {
        if (this.request.compareAndSet(null, request))
        {
            long delay = timeoutAt - System.nanoTime();
            if (LOG.isDebugEnabled())
                LOG.debug("Scheduled timeout in {} ms for {}", TimeUnit.NANOSECONDS.toMillis(delay), request);
            if (delay <= 0)
                onTimeoutExpired();
            else
                schedule(delay, TimeUnit.NANOSECONDS);
        }
    }
}
