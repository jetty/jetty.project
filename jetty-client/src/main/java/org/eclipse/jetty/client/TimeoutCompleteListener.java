//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.io.CyclicTimeoutTask;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class TimeoutCompleteListener extends CyclicTimeoutTask implements Response.CompleteListener
{
    private static final Logger LOG = Log.getLogger(TimeoutCompleteListener.class);

    private final AtomicReference<Request> request = new AtomicReference<Request>();

    public TimeoutCompleteListener(Scheduler scheduler)
    {
        super(scheduler);
    }

    @Override
    protected void onTimeoutExpired()
    {
        Request request = this.request.getAndSet(null);
        if (LOG.isDebugEnabled())
            LOG.debug("onTimeoutExpired for {}", request);
        if (request!=null)
            request.abort(new TimeoutException("Total timeout " + request.getTimeout() + " ms elapsed"));
    }
    
    @Override
    public void onComplete(Result result)
    {
        Request request = this.request.getAndSet(null);
        if (cancel())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled timeout: {} for {}", result, request);
        }
    }

    public boolean schedule(HttpRequest request)
    {
        long timeoutInMs = request.timeoutIn(System.nanoTime(),MILLISECONDS);
        if (LOG.isDebugEnabled())
            LOG.debug("Scheduled timeout {} ms for {}", timeoutInMs, request);
        if (timeoutInMs>=0 && this.request.compareAndSet(null,request))
        {
            schedule(timeoutInMs, TimeUnit.MILLISECONDS);
            return true;
        }
        
        return false;        
    }


}
