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

package org.eclipse.jetty.spdy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.api.Handler;

/**
 * <p>A {@link Promise} is a {@link Future} that allows a result or a failure to be set,
 * so that the {@link Future} will be {@link #isDone() done}.</p>
 *
 * @param <T> the type of the result object
 */
public class Promise<T> implements Handler<T>, Future<T>
{
    private final CountDownLatch latch = new CountDownLatch(1);
    private boolean cancelled;
    private Throwable failure;
    private T promise;

    @Override
    public void completed(T result)
    {
        this.promise = result;
        latch.countDown();
    }

    @Override
    public void failed(T context, Throwable x)
    {
        this.failure = x;
        latch.countDown();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        cancelled = true;
        latch.countDown();
        return true;
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    @Override
    public boolean isDone()
    {
        return cancelled || latch.getCount() == 0;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException
    {
        latch.await();
        return result();
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        boolean elapsed = !latch.await(timeout, unit);
        if (elapsed)
            throw new TimeoutException();
        return result();
    }

    private T result() throws ExecutionException
    {
        if (isCancelled())
            throw new CancellationException();
        Throwable failure = this.failure;
        if (failure != null)
            throw new ExecutionException(failure);
        return promise;
    }
}
