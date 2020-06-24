//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeoutCompleteListener extends CyclicTimeout implements Response.CompleteListener
{
    private static final Logger LOG = LoggerFactory.getLogger(TimeoutCompleteListener.class);

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
            LOG.debug("Total timeout {} ms elapsed for {} on {}", request.getTimeout(), request, this);
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
                LOG.debug("Cancelled ({}) timeout for {} on {}", cancelled, request, this);
        }
    }

    void schedule(HttpRequest request, long timeoutAt)
    {
        if (this.request.compareAndSet(null, request))
        {
            long delay = timeoutAt - System.nanoTime();
            if (delay <= 0)
            {
                onTimeoutExpired();
            }
            else
            {
                schedule(delay, TimeUnit.NANOSECONDS);
                if (LOG.isDebugEnabled())
                    LOG.debug("Scheduled timeout in {} ms for {} on {}", TimeUnit.NANOSECONDS.toMillis(delay), request, this);
            }
        }
    }
}
