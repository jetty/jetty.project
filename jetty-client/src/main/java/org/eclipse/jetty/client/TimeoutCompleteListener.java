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

package org.eclipse.jetty.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated Do not use it, use {@link CyclicTimeouts} instead.
 */
@Deprecated
public class TimeoutCompleteListener extends CyclicTimeout implements Response.CompleteListener
{
    private static final Logger LOG = LoggerFactory.getLogger(TimeoutCompleteListener.class);

    private final AtomicReference<Request> requestTimeout = new AtomicReference<>();

    public TimeoutCompleteListener(Scheduler scheduler)
    {
        super(scheduler);
    }

    @Override
    public void onTimeoutExpired()
    {
        Request request = requestTimeout.getAndSet(null);
        if (LOG.isDebugEnabled())
            LOG.debug("Total timeout {} ms elapsed for {} on {}", request.getTimeout(), request, this);
        if (request != null)
            request.abort(new TimeoutException("Total timeout " + request.getTimeout() + " ms elapsed"));
    }

    @Override
    public void onComplete(Result result)
    {
        Request request = requestTimeout.getAndSet(null);
        if (request != null)
        {
            boolean cancelled = cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled ({}) timeout for {} on {}", cancelled, request, this);
        }
    }

    void schedule(HttpRequest request, long timeoutAt)
    {
        if (requestTimeout.compareAndSet(null, request))
        {
            long delay = Math.max(0, NanoTime.until(timeoutAt));
            if (LOG.isDebugEnabled())
                LOG.debug("Scheduling timeout in {} ms for {} on {}", TimeUnit.NANOSECONDS.toMillis(delay), request, this);
            schedule(delay, TimeUnit.NANOSECONDS);
        }
    }
}
