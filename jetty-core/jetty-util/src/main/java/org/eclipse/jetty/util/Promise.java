//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * @param <C> the type of the context object
 */
public interface Promise<C>
{
    Promise<Object> NOOP = new Promise<>()
    {
    };

    @SuppressWarnings("unchecked")
    static <T> Promise<T> noop()
    {
        return (Promise<T>)NOOP;
    }

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
     * <p>Completes this promise with the given {@link CompletableFuture}.</p>
     * <p>When the CompletableFuture completes normally, this promise is succeeded;
     * when the CompletableFuture completes exceptionally, this promise is failed.</p>
     *
     * @param completable the CompletableFuture that completes this promise
     */
    default void completeWith(CompletableFuture<C> completable)
    {
        completable.whenComplete((o, x) ->
        {
            if (x == null)
                succeeded(o);
            else
                failed(x);
        });
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
            LoggerFactory.getLogger(this.getClass()).warn("Failed", x);
        }
    }

    /**
     * <p>Creates a Promise from the given success and failure consumers.</p>
     *
     * @param success the consumer invoked when the promise is succeeded
     * @param failure the consumer invoked when the promise is failed
     * @param <T> the type of the result
     * @return a new Promise wrapping the success and failure consumers.
     */
    static <T> Promise<T> from(Consumer<T> success, Consumer<Throwable> failure)
    {
        return new Promise<>()
        {
            @Override
            public void succeeded(T result)
            {
                success.accept(result);
            }

            @Override
            public void failed(Throwable x)
            {
                failure.accept(x);
            }
        };
    }

    /**
     * <p>Creates a promise from the given incomplete CompletableFuture.</p>
     * <p>When the promise completes, either succeeding or failing, the
     * CompletableFuture is also completed, respectively via
     * {@link CompletableFuture#complete(Object)} or
     * {@link CompletableFuture#completeExceptionally(Throwable)}.</p>
     *
     * @param completable the CompletableFuture to convert into a promise
     * @param <T> the type of the result
     * @return a promise that when completed, completes the given CompletableFuture
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
        /**
         * <p>Creates a new {@code Completable} to be consumed by the given
         * {@code consumer}, then returns the newly created {@code Completable}.</p>
         *
         * @param consumer the code that consumes the newly created {@code Completable}
         * @return the newly created {@code Completable}
         * @param <R> the type of the result
         */
        public static <R> Completable<R> with(Consumer<Promise<R>> consumer)
        {
            Completable<R> completable = new Completable<>();
            consumer.accept(completable);
            return completable;
        }

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
