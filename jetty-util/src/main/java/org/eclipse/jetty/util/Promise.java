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

package org.eclipse.jetty.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.util.log.Log;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * @param <C> the type of the context object
 */
public interface Promise<C>
{
    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @param result the context
     * @see #failed(Throwable)
     */
    default void succeeded(C result)
    {
    }

    /**
     * <p>Callback invoked when the operation fails.</p>
     *
     * @param x the reason for the operation failure
     */
    default void failed(Throwable x)
    {
    }

    /**
     * <p>Empty implementation of {@link Promise}.</p>
     *
     * @param <U> the type of the result
     */
    class Adapter<U> implements Promise<U>
    {
        @Override
        public void failed(Throwable x)
        {
            Log.getLogger(this.getClass()).warn(x);
        }
    }

    /**
     * <p>Creates a promise from the given incomplete CompletableFuture.</p>
     * <p>When the promise completes, either succeeding or failing, the
     * CompletableFuture is also completed, respectively via
     * {@link CompletableFuture#complete(Object)} or
     * {@link CompletableFuture#completeExceptionally(Throwable)}.</p>
     *
     * @param completable the CompletableFuture to convert into a promise
     * @return a promise that when completed, completes the given CompletableFuture
     * @param <T> the type of the result
     */
    static <T> Promise<T> from(CompletableFuture<? super T> completable)
    {
        if (completable instanceof Promise)
            return (Promise<T>)completable;

        return new Promise<T>()
        {
            @Override
            public void succeeded(T result)
            {
                completable.complete(result);
            }

            @Override
            public void failed(Throwable x)
            {
                completable.completeExceptionally(x);
            }
        };
    }

    /**
     * <p>A CompletableFuture that is also a Promise.</p>
     *
     * @param <S> the type of the result
     */
    class Completable<S> extends CompletableFuture<S> implements Promise<S>
    {
        @Override
        public void succeeded(S result)
        {
            complete(result);
        }

        @Override
        public void failed(Throwable x)
        {
            completeExceptionally(x);
        }
    }

    class Wrapper<W> implements Promise<W>
    {
        private final Promise<W> promise;

        public Wrapper(Promise<W> promise)
        {
            this.promise = Objects.requireNonNull(promise);
        }

        @Override
        public void succeeded(W result)
        {
            promise.succeeded(result);
        }

        @Override
        public void failed(Throwable x)
        {
            promise.failed(x);
        }

        public Promise<W> getPromise()
        {
            return promise;
        }

        public Promise<W> unwrap()
        {
            Promise<W> result = promise;
            while (true)
            {
                if (result instanceof Wrapper)
                    result = ((Wrapper<W>)result).unwrap();
                else
                    break;
            }
            return result;
        }
    }
}
