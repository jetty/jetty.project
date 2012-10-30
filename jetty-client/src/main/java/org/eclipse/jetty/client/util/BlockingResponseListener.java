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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;

public class BlockingResponseListener extends BufferingResponseListener implements Future<ContentResponse>
{
    private final CountDownLatch latch = new CountDownLatch(1);
    private final Request request;
    private ContentResponse response;
    private Throwable failure;
    private volatile boolean cancelled;

    public BlockingResponseListener(Request request)
    {
        this.request = request;
    }

    @Override
    public void onComplete(Result result)
    {
        response = new HttpContentResponse(result.getResponse(), getContent(), getEncoding());
        failure = result.getFailure();
        latch.countDown();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        cancelled = true;
        return request.abort("Cancelled");
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    @Override
    public boolean isDone()
    {
        return latch.getCount() == 0 || isCancelled();
    }

    @Override
    public ContentResponse get() throws InterruptedException, ExecutionException
    {
        latch.await();
        return getResult();
    }

    @Override
    public ContentResponse get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        boolean expired = !latch.await(timeout, unit);
        if (expired)
        {
            request.abort("Total timeout elapsed");
            throw new TimeoutException();
        }
        return getResult();
    }

    private ContentResponse getResult() throws ExecutionException
    {
        if (isCancelled())
            throw (CancellationException)new CancellationException().initCause(failure);
        if (failure != null)
            throw new ExecutionException(failure);
        return response;
    }
}
