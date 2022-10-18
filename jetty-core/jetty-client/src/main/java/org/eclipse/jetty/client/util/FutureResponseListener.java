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

package org.eclipse.jetty.client.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;

/**
 * A {@link BufferingResponseListener} that is also a {@link Future}, to allow applications
 * to block (indefinitely or for a timeout) until {@link #onComplete(Result)} is called,
 * or to {@link #cancel(boolean) abort} the request/response conversation.
 * <p>
 * Typical usage is:
 * <pre>
 * Request request = httpClient.newRequest(...)...;
 * FutureResponseListener listener = new FutureResponseListener(request);
 * request.send(listener); // Asynchronous send
 * ContentResponse response = listener.get(5, TimeUnit.SECONDS); // Timed block
 * </pre>
 */
public class FutureResponseListener extends BufferingResponseListener implements Future<ContentResponse>
{
    private final CountDownLatch latch = new CountDownLatch(1);
    private final Request request;
    private ContentResponse response;
    private Throwable failure;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public FutureResponseListener(Request request)
    {
        this(request, 2 * 1024 * 1024);
    }

    public FutureResponseListener(Request request, int maxLength)
    {
        super(maxLength);
        this.request = request;
    }

    public Request getRequest()
    {
        return request;
    }

    @Override
    public void onComplete(Result result)
    {
        response = new HttpContentResponse(result.getResponse(), getContent(), getMediaType(), getEncoding());
        failure = result.getFailure();
        latch.countDown();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        if (cancelled.compareAndSet(false, true))
        {
            request.abort(new CancellationException());
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled.get();
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
            throw new TimeoutException();
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
