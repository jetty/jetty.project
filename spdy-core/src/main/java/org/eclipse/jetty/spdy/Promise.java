/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

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

    public void failed(Throwable x)
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
        latch.await(timeout, unit);
        return result();
    }

    private T result() throws ExecutionException
    {
        Throwable failure = this.failure;
        if (failure != null)
            throw new ExecutionException(failure);
        return promise;
    }
}
