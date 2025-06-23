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
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
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
     * Creates a promise that runs the given {@link Runnable} when it succeeds or fails.
     *
     * @param complete The completion task to run on success or failure
     * @return a new promise
     */
    static <T> Promise<T> from(Runnable complete)
    {
        return new Promise<>()
        {
            @Override
            public void succeeded(T result)
            {
                complete.run();
            }

            @Override
            public void failed(Throwable x)
            {
                complete.run();
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

    /**
     * An {@link org.eclipse.jetty.util.thread.Invocable} {@link Promise} that provides the
     * {@link InvocationType} of calls to {@link Promise#succeeded(Object)}.
     * Also provides the {@link BiConsumer} interface as a convenient for working
     * with {@link CompletableFuture}.
     * @param <R> The result type
     */
    interface Invocable<R> extends org.eclipse.jetty.util.thread.Invocable, Promise<R>, BiConsumer<R, Throwable>
    {
        @Override
        default void accept(R result, Throwable error)
        {
            if (error != null)
                failed(error);
            else
                succeeded(result);
        }
    }

    /**
     * Create an {@link Promise.Invocable}
     * @param invocationType The {@link org.eclipse.jetty.util.thread.Invocable.InvocationType} of calls to the {@link Invocable}
     * @param promise The promise on which to delegate calls to.
     * @param <C> The type
     * @return An {@link org.eclipse.jetty.util.thread.Invocable} {@link Promise}.
     */
    static <C> Invocable<C> from(org.eclipse.jetty.util.thread.Invocable.InvocationType invocationType, Promise<C> promise)
    {
        return new Invocable<C>()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public void succeeded(C result)
            {
                promise.succeeded(result);
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }
        };
    }

    /**
     * Create an {@link Invocable} that is {@link org.eclipse.jetty.util.thread.Invocable.InvocationType#NON_BLOCKING} because
     * it executes the callbacks
     * @param promise The promise on which to delegate calls to.
     * @param <C> The type
     * @return An {@link org.eclipse.jetty.util.thread.Invocable} {@link Promise}.
     */
    static <C> Invocable<C> from(Executor executor, Promise<C> promise)
    {
        Objects.requireNonNull(executor);
        return new Invocable<C>()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void succeeded(C result)
            {
                executor.execute(() -> promise.succeeded(result));
            }

            @Override
            public void failed(Throwable x)
            {
                executor.execute(() -> promise.failed(x));
            }
        };
    }

    /**
     * <p>A {@link Promise} that implements {@link Runnable} to perform
     * a one-shot task that eventually completes this {@link Promise}.</p>
     * <p>Subclasses override {@link #run()} to implement the task.</p>
     * <p>Users of this class start the task execution via {@link #run()}.</p>
     * <p>Typical usage:</p>
     * <pre>{@code
     * // Specify what to do in case of success and failure.
     * Promise.Task<T> task = new Promise.Task<>(() -> onSuccess(), x -> onFailure(x))
     * {
     *     @Override
     *     public void run()
     *     {
     *         try
     *         {
     *             // Perform some task.
     *             T result = performTask();
     *
     *             // Eventually succeed this Promise.
     *             succeeded(result);
     *         }
     *         catch (Throwable x)
     *         {
     *             // Fail this Promise.
     *             failed(x);
     *         }
     *     }
     * }
     *
     * // Start the task.
     * task.run();
     * }</pre>
     *
     * @param <T> the type of the result of the task
     */
    abstract class Task<T> implements Promise<T>, Runnable
    {
        private final Runnable onSuccess;
        private final Consumer<Throwable> onFailure;

        public Task()
        {
            onSuccess = null;
            onFailure = null;
        }

        public Task(Runnable onSuccess, Consumer<Throwable> onFailure)
        {
            this.onSuccess = Objects.requireNonNull(onSuccess);
            this.onFailure = Objects.requireNonNull(onFailure);
        }

        @Override
        public void succeeded(T result)
        {
            if (onSuccess != null)
                onSuccess.run();
        }

        @Override
        public void failed(Throwable x)
        {
            if (onFailure != null)
                onFailure.accept(x);
        }
    }
}
