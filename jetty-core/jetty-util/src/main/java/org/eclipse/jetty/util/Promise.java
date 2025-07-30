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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <p>An abstraction for a completed/failed result of an asynchronous operation.</p>
 *
 * @param <C> the type of the promise result
 */
public interface Promise<C>
{
    /**
     * <p>Callback method to invoke when the operation succeeds.</p>
     *
     * @param result the operation result
     * @see #failed(Throwable)
     */
    default void succeeded(C result)
    {
    }

    /**
     * <p>Callback method to invoke when the operation fails.</p>
     *
     * @param x the operation failure
     */
    default void failed(Throwable x)
    {
    }

    /**
     * @return a promise that performs no operations.
     * @param <T> the type of the promise result
     */
    static <T> Promise<T> noop()
    {
        return Promise.Invocable.noop();
    }

    /**
     * <p>Completes the given promise with the given {@link CompletableFuture}.</p>
     * <p>When the CompletableFuture completes normally, the given promise is succeeded;
     * when the CompletableFuture completes exceptionally, the given promise is failed.</p>
     *
     * @param promise the promise to complete
     * @param completable the {@link CompletableFuture} that completes the promise
     */
    static <T> void completeWith(Promise<T> promise, CompletableFuture<T> completable)
    {
        completable.whenComplete((o, x) ->
        {
            if (x == null)
                promise.succeeded(o);
            else
                promise.failed(x);
        });
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

        return new Promise<>()
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

        public Promise<W> getWrapped()
        {
            return promise;
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
    }

    /**
     * An {@link org.eclipse.jetty.util.thread.Invocable} {@link Promise} that provides
     * the {@link InvocationType} of calls to {@link Promise#succeeded(Object)}.
     *
     * @param <R> The result type
     */
    interface Invocable<R> extends org.eclipse.jetty.util.thread.Invocable, Promise<R>
    {
        /**
         * @return a promise that performs no operations.
         * @param <T> the type of the promise result
         */
        @SuppressWarnings("unchecked")
        static <T> Promise.Invocable<T> noop()
        {
            return (Promise.Invocable<T>)NoOp.NOOP;
        }

        /**
         * <p>Returns a new {@link Callback} that, when it is completed, completes the given promise.</p>
         *
         * @param promise the promise to wrap
         * @param result the result to use to succeed the given promise when the {@link Callback} succeeds
         * @return a new {@link Callback} wrapping the given promise
         */
        static <T> Callback toCallback(Promise.Invocable<T> promise, T result)
        {
            return Callback.from(promise.getInvocationType(), () -> promise.succeeded(result), promise::failed);
        }

        /**
         * <p>Returns a new promise that, when it is completed, completes the given promise.</p>
         *
         * @param promise the promise to wrap
         * @param mapper a function that converts the result type
         * @return a new promise wrapping the given promise
         * @param <W> the wrapper type
         * @param <T> the promise type
         */
        static <W, T> Promise.Invocable<W> toPromise(Promise.Invocable<T> promise, Function<W, T> mapper)
        {
            return new Abstract<>(promise.getInvocationType())
            {
                @Override
                public void succeeded(W result)
                {
                    promise.succeeded(mapper.apply(result));
                }

                @Override
                public void failed(Throwable x)
                {
                    promise.failed(x);
                }
            };
        }

        /**
         * <p>Returns a new promise that, when it is completed, completes the given {@link CompletableFuture}.</p>
         *
         * @param completable the {@link CompletableFuture} to complete
         * @return a new promise
         * @param <W> the result type
         */
        static <W> Promise.Invocable<W> toPromise(CompletableFuture<W> completable)
        {
            return new Abstract<>(InvocationType.BLOCKING)
            {
                @Override
                public void succeeded(W result)
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
         * <p>Returns a {@link BiConsumer} that, when it is invoked, completes the given promise</p>
         * <p>Typical usage is with {@link CompletableFuture#whenComplete(BiConsumer)}:</p>
         * <pre>{@code
         * void example(Promise<T> promise) {
         *     CompletableFuture<T> completable = ...;
         *     completable.whenComplete(Promise.Invocable.toBiConsumer(promise));
         * }
         * }</pre>
         *
         * @param promise the promise to wrap
         */
        static <R> BiConsumer<R, Throwable> toBiConsumer(Promise.Invocable<R> promise)
        {
            return new InvocableBiConsumer<>(promise.getInvocationType())
            {
                @Override
                public void accept(R result, Throwable failure)
                {
                    if (failure == null)
                        promise.succeeded(result);
                    else
                        promise.failed(failure);
                }
            };
        }

        /**
         * <p>Factory method to create a promise from the given arguments.</p>
         *
         * @param invocationType the {@link InvocationType} of the promise
         * @param success the consumer to run upon success
         * @param failure the consumer to run upon failure
         * @return a new promise
         * @param <T> the type of the promise result
         */
        static <T> Promise.Invocable<T> from(InvocationType invocationType, Consumer<T> success, Consumer<Throwable> failure)
        {
            return new Abstract<>(invocationType)
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
         * <p>Factory method to create a promise from the given arguments.</p>
         *
         * @param invocationType the {@link InvocationType} of the promise
         * @param consumer the consumer to run upon completion
         * @return a new promise
         * @param <T> the type of the promise result
         */
        static <T> Promise.Invocable<T> from(InvocationType invocationType, BiConsumer<T, Throwable> consumer)
        {
            return new Abstract<>(invocationType)
            {
                @Override
                public void succeeded(T result)
                {
                    consumer.accept(result, null);
                }

                @Override
                public void failed(Throwable x)
                {
                    consumer.accept(null, x);
                }
            };
        }

        /**
         * <p>Returns a promise that, when it is completed, completes
         * the given promise and then runs the given {@link Runnable}.</p>
         *
         * @param promise the promise to wrap
         * @param afterComplete the {@link Runnable} to run after completion
         * @return a new promise wrapping the given promise
         * @param <T> the type of the promise result
         * @see #from(Runnable, Invocable)
         */
        static <T> Promise.Invocable<T> from(Promise.Invocable<T> promise, Runnable afterComplete)
        {
            return new Abstract<>(promise.getInvocationType())
            {
                @Override
                public void succeeded(T result)
                {
                    try
                    {
                        promise.succeeded(result);
                    }
                    finally
                    {
                        afterComplete.run();
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    try
                    {
                        promise.failed(x);
                    }
                    finally
                    {
                        afterComplete.run();
                    }
                }
            };
        }

        /**
         * <p>Returns a promise that, when it is completed, runs the
         * given {@link Runnable} and then completes the given promise.</p>
         *
         * @param beforeComplete the {@link Runnable} to run before completion
         * @param promise the promise to wrap
         * @return a new promise wrapping the given promise
         * @param <T> the type of the promise result
         * @see #from(Invocable, Runnable)
         */
        static <T> Promise.Invocable<T> from(Runnable beforeComplete, Promise.Invocable<T> promise)
        {
            return new Abstract<>(promise.getInvocationType())
            {
                @Override
                public void succeeded(T result)
                {
                    try
                    {
                        beforeComplete.run();
                    }
                    finally
                    {
                        promise.succeeded(result);
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    try
                    {
                        beforeComplete.run();
                    }
                    finally
                    {
                        promise.failed(x);
                    }
                }
            };
        }

        /**
         * <p>Abstract implementation of {@link Promise.Invocable}
         * with the specified {@link InvocationType}.</p>
         *
         * @param <T> the type of the promise result
         */
        abstract class Abstract<T> implements Invocable<T>
        {
            private final InvocationType invocationType;

            protected Abstract(InvocationType invocationType)
            {
                this.invocationType = invocationType;
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }
        }

        /**
         * <p>Abstract implementation of {@link Promise.Invocable} with
         * {@link InvocationType} set to {@link InvocationType#NON_BLOCKING}.</p>
         *
         * @param <T> the type of the promise result
         */
        abstract class NonBlocking<T> extends Abstract<T>
        {
            public NonBlocking()
            {
                super(InvocationType.NON_BLOCKING);
            }
        }
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

// @checkstyle-disable-check : OneTopLevelClass
class NoOp extends Promise.Invocable.NonBlocking<Object>
{
    static NoOp NOOP = new NoOp();
}

// @checkstyle-disable-check : OneTopLevelClass
abstract class InvocableBiConsumer<R> implements BiConsumer<R, Throwable>, org.eclipse.jetty.util.thread.Invocable
{
    private final InvocationType invocationType;

    InvocableBiConsumer(InvocationType invocationType)
    {
        this.invocationType = invocationType;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }
}
