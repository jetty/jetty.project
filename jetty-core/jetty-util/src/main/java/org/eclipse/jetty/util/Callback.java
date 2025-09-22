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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * <p>Semantically this is equivalent to an optimise Promise&lt;Void&gt;, but callback is a more meaningful
 * name than EmptyPromise</p>
 */
public interface Callback extends Invocable
{
    /**
     * Instance of Callback that can be used when the callback methods need an empty
     * implementation without incurring in the cost of allocating a new Callback object.
     */
    Callback NOOP = new Callback()
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public String toString()
        {
            return "Callback.NOOP";
        }
    };

    /**
     * Instance of Callback to use in cases where it is known that no one will complete the callback.
     **/
    Callback NOT_CALLED = new Callback()
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void succeeded()
        {
            throw new IllegalStateException();
        }

        @Override
        public void failed(Throwable x)
        {
            throw new IllegalStateException(x);
        }

        @Override
        public String toString()
        {
            return "Callback.NOT_CALLED";
        }
    };

    /**
     * <p>Completes this callback with the given {@link CompletableFuture}.</p>
     * <p>When the CompletableFuture completes normally, this callback is succeeded;
     * when the CompletableFuture completes exceptionally, this callback is failed.</p>
     *
     * @param completable the CompletableFuture that completes this callback
     */
    default void completeWith(CompletableFuture<?> completable)
    {
        completable.whenComplete((o, x) ->
        {
            if (x == null)
                succeeded();
            else
                failed(x);
        });
    }

    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @see #failed(Throwable)
     */
    default void succeeded()
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
     * <p>Creates a non-blocking callback from the given incomplete CompletableFuture.</p>
     * <p>When the callback completes, either succeeding or failing, the
     * CompletableFuture is also completed, respectively via
     * {@link CompletableFuture#complete(Object)} or
     * {@link CompletableFuture#completeExceptionally(Throwable)}.</p>
     *
     * @param completable the CompletableFuture to convert into a callback
     * @return a callback that when completed, completes the given CompletableFuture
     */
    static Callback from(CompletableFuture<?> completable)
    {
        return from(completable, InvocationType.NON_BLOCKING);
    }

    /**
     * <p>Creates a callback from the given incomplete CompletableFuture,
     * with the given {@code blocking} characteristic.</p>
     *
     * @param completable the CompletableFuture to convert into a callback
     * @param invocationType whether the callback is blocking
     * @return a callback that when completed, completes the given CompletableFuture
     */
    static Callback from(CompletableFuture<?> completable, InvocationType invocationType)
    {
        if (completable instanceof Callback)
            return (Callback)completable;

        return new Callback()
        {
            @Override
            public void succeeded()
            {
                completable.complete(null);
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    completable.completeExceptionally(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }
        };
    }

    /**
     * Creates a callback from the given success and failure lambdas.
     *
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails
     * @return a new Callback
     */
    static Callback from(Runnable success, Consumer<Throwable> failure)
    {
        return from(InvocationType.BLOCKING, success, failure);
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param invocationType the Callback invocation type
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails
     * @return a new Callback
     */
    static Callback from(InvocationType invocationType, Runnable success, Consumer<Throwable> failure)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                success.run();
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    failure.accept(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback@%x{%s, %s,%s}".formatted(hashCode(), invocationType, success, failure);
            }
        };
    }

    /**
     * Creates a callback that runs completed when it succeeds or fails
     *
     * @param completed The completion to run on success or failure
     * @return a new callback
     */
    static Callback from(Runnable completed)
    {
        return from(Invocable.getInvocationType(completed), completed);
    }

    /**
     * <p>Creates a Callback with the given {@code invocationType},
     * that runs the given {@code Runnable} when it succeeds or fails.</p>
     *
     * @param invocationType the invocation type of the returned Callback
     * @param completed the Runnable to run when the callback either succeeds or fails
     * @return a new Callback with the given invocation type
     */
    static Callback from(InvocationType invocationType, Runnable completed)
    {
        return new Completing()
        {
            @Override
            public void completed()
            {
                completed.run();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback.Completing@%x{%s,%s}".formatted(hashCode(), invocationType, completed);
            }
        };
    }

    /**
     * <p>Creates a callback that runs the {@link Runnable} argument after
     * completing the callback argument.</p>
     * <p>The {@link Runnable} is assumed to be {@code NON_BLOCKING}, so the
     * {@link InvocationType} of the returned callback is the same as the
     * callback argument.</p>
     *
     * @param callback The nested callback
     * @param completed The {@link Runnable} to run after the nested callback
     * is completed
     * @return a new callback
     */
    static Callback from(Callback callback, Runnable completed)
    {
        return new Nested(callback)
        {
            public void completed()
            {
                completed.run();
            }
        };
    }

    /**
     * <p>Creates a callback that runs the {@link Consumer} argument after
     * completing the callback argument.</p>
     * <p>The {@link Consumer} receives {@code null} if the callback succeeded,
     * or the {@link Throwable} failure if the callback failed.</p>
     * <p>The {@link Consumer} is assumed to be {@code NON_BLOCKING}, so the
     * {@link InvocationType} of the returned callback is the same as the
     * callback parameter.</p>
     *
     * @param callback The nested callback
     * @param completed The {@link Consumer} to run after the nested callback
     * is completed
     * @return a new callback
     */
    static Callback from(Callback callback, Consumer<Throwable> completed)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                try
                {
                    callback.succeeded();
                }
                finally
                {
                    completed.accept(null);
                }
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(callback::failed, completed, x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        };
    }

    /**
     * Creates a callback that runs the {@link Runnable} argument before
     * completing the callback argument.
     * <p>The {@link Runnable} is assumed to be {@code NON_BLOCKING}, so the
     * {@link InvocationType} of the returned callback is the same as the
     * callback argument.</p>
     *
     * @param callback The nested callback
     * @param completed The {@link Runnable} to run before the nested callback
     * is completed. Any exceptions thrown from the {@link Runnable} will
     * result in the callback failure.
     * @return a new callback
     */
    static Callback from(Runnable completed, Callback callback)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                try
                {
                    completed.run();
                    callback.succeeded();
                }
                catch (Throwable t)
                {
                    Callback.failed(callback, t);
                }
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(this::completed, callback::failed, x);
            }

            private void completed(Throwable ignored)
            {
                completed.run();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        };
    }

    /**
     * <p>Creates a callback which always fails the callback argument on completion.</p>
     *
     * @param callback The nested callback
     * @param failure The cause to fail the nested callback; if the new callback is failed,
     * the cause will be added to the failure argument as a suppressed exception.
     * @return a new callback.
     */
    static Callback from(Callback callback, Throwable failure)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                callback.failed(failure);
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(failure, x);
                Callback.failed(callback, failure);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        };
    }

    /**
     * Creates a callback which combines two other callbacks and will succeed or fail them both.
     * @param callback1 The first callback
     * @param callback2 The second callback
     * @return a new callback.
     */
    static Callback from(Callback callback1, Callback callback2)
    {
        return combine(callback1, callback2);
    }

    /**
     * A combination of multiple Callbacks, that must all be completed before a specific callback is completed.
     * For example: <pre>{@code
     *   void sendToAll(String message, Collection<Channel> channels, Callback callback)
     *   {
     *       try (Callback.Combination combination = new Callback.Combination(callback))
     *       {
     *           for (Channel channel : channels)
     *               channel.send(message, combination.newCallback());
     *       }
     *   }
     * }</pre>
     */
    class Combination implements AutoCloseable
    {
        private final Callback andThen;
        private final AtomicStampedReference<Throwable> state;

        /**
         * Create a new empty combined callback.
         * @param andThen The {@code Callback} to complete once all {@code Callbacks} in the combination are complete
         *                and this combination is {@link #close() closed}.
         */
        public Combination(Callback andThen)
        {
            this(andThen, null);
        }

        /**
         * Create a new empty combined callback with a forced failure and invocation type.
         *
         * @param andThen The {@code Callback} to complete once all {@code Callbacks} in the combination are complete
         *                and this combination is {@link #close() closed}.
         * @param failure If not {@code null}, force a failure, so that the {@link Callback#failed(Throwable)} method
         * will always be called on the {@code Callback} passed to the constructor, once all the
         * combined callbacks are completed and the {@code Combination} is {@link #close() closed}.
         */
        public Combination(Callback andThen, Throwable failure)
        {
            this.andThen = Objects.requireNonNull(andThen);
            // initial stamp is -1 to indicate not closed and requiring 1 completion from close.
            this.state = new AtomicStampedReference<>(failure, -1);
        }

        /**
         * Create a new {@code Callback} as part of this combination.
         *
         * @return A {@code Callback} that must be completed before the callback passed to
         *         the constructor is completed.
         * @throws IllegalStateException if the combination has already been completed.
         */
        public Callback newCallback()
        {
            int[] h = new int[1];
            while (true)
            {
                Throwable failure = state.get(h);
                int s = h[0];

                if (s >= 0)
                    throw new IllegalStateException("closed");
                // we can only create new callbacks when not closed, so we decrement to make a more negative stamp.
                if (!state.compareAndSet(failure, failure, s, s - 1))
                    continue;

                return new Callback()
                {
                    private final AtomicBoolean completed = new AtomicBoolean(false);

                    @Override
                    public void failed(Throwable x)
                    {
                        if (completed.compareAndSet(false, true))
                            complete(x);
                    }

                    @Override
                    public void succeeded()
                    {
                        if (completed.compareAndSet(false, true))
                            complete(null);
                    }

                    @Override
                    public InvocationType getInvocationType()
                    {
                        return andThen.getInvocationType();
                    }
                };
            }
        }

        /**
         * Called to indicate that no more calls to {@link #newCallback()} will happen.
         * If the combination is already complete, then it will be completed in the scope of this call.
         *
         * @throws IllegalStateException if this method has already been called.
         */
        @Override
        public void close() throws IllegalStateException
        {
            int[] h = new int[1];
            while (true)
            {
                Throwable failure = state.get(h);
                int s = h[0];

                if (s >= 0)
                    throw new IllegalStateException("closed");

                // make the stamp positive to indicate that the combination is closed
                if (!state.compareAndSet(failure, failure, s, -s))
                    continue;
                complete(null);
                return;
            }
        }

        private void complete(Throwable failed)
        {
            Throwable failure;
            int[] h = new int[1];
            while (true)
            {
                failure = state.get(h);
                int s = h[0];

                // combine failures
                Throwable combined = failure;
                if (combined == null)
                    combined = failed;
                else if (failed != null)
                    ExceptionUtil.addSuppressedIfNotAssociated(failure, failed);

                // If the stamp is < 0, the combination has not been closed, so we increment, else we decrement.
                int n = s < 0 ? s + 1 : s - 1;
                if (!state.compareAndSet(failure, combined, s, n))
                    continue;

                if (n == 0)
                {
                    if (failure == null)
                        andThen.succeeded();
                    else
                        andThen.failed(failure);
                }

                return;
            }
        }
    }

    /**
     * <p>A Callback implementation that calls the {@link #completed()} method when it either succeeds or fails.</p>
     */
    interface Completing extends Callback
    {
        void completed();

        @Override
        default void succeeded()
        {
            completed();
        }

        @Override
        default void failed(Throwable x)
        {
            try
            {
                completed();
            }
            catch (Throwable t)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                throw t;
            }
        }
    }

    /**
     * Nested Completing Callback that completes after
     * completing the nested callback
     */
    class Nested implements Completing
    {
        private final Callback callback;

        public Nested(Callback callback)
        {
            this.callback = Objects.requireNonNull(callback);
        }

        public Callback getCallback()
        {
            return callback;
        }

        @Override
        public void completed()
        {
        }

        private void completed(Throwable ignored)
        {
            completed();
        }

        @Override
        public void succeeded()
        {
            try
            {
                callback.succeeded();
            }
            finally
            {
                completed();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            Callback.failed(callback::failed, this::completed, x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return callback.getInvocationType();
        }

        @Override
        public String toString()
        {
            return "%s@%x:%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), callback);
        }
    }

    static Callback combine(Callback cb1, Callback cb2)
    {
        if (cb1 == null || cb1 == cb2)
            return cb2;
        if (cb2 == null)
            return cb1;

        return new Callback()
        {
            @Override
            public void succeeded()
            {
                try
                {
                    cb1.succeeded();
                }
                finally
                {
                    cb2.succeeded();
                }
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(cb1::failed, cb2::failed, x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.combine(cb1.getInvocationType(), cb2.getInvocationType());
            }
        };
    }

    /**
     * <p>A {@link CompletableFuture} that is also a {@link Callback}.</p>
     */
    class Completable extends CompletableFuture<Void> implements Callback
    {
        /**
         * <p>Creates a new {@code Completable} to be consumed by the given
         * {@code consumer}, then returns the newly created {@code Completable}.</p>
         *
         * @param consumer the code that consumes the newly created {@code Completable}
         * @return the newly created {@code Completable}
         */
        public static Completable with(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            consumer.accept(completable);
            return completable;
        }

        /**
         * Creates a completable future given a callback.
         *
         * @param callback The nested callback.
         * @return a new Completable which will succeed this callback when completed.
         */
        public static Completable from(Callback callback)
        {
            return new Completable(callback.getInvocationType())
            {
                @Override
                public void succeeded()
                {
                    callback.succeeded();
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    Callback.failed(callback::failed, super::failed, x);
                }
            };
        }

        private final InvocationType invocationType;

        public Completable()
        {
            this(Invocable.InvocationType.NON_BLOCKING);
        }

        public Completable(InvocationType invocationType)
        {
            this.invocationType = invocationType;
        }

        @Override
        public void succeeded()
        {
            complete(null);
        }

        @Override
        public void failed(Throwable x)
        {
            completeExceptionally(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return invocationType;
        }

        /**
         * <p>Returns a new {@link Completable} that, when this {@link Completable}
         * succeeds, is passed to the given consumer and then returned.</p>
         * <p>If this {@link Completable} fails, the new {@link Completable} is
         * also failed, and the consumer is not invoked.</p>
         *
         * @param consumer the consumer that receives the {@link Completable}
         * @return a new {@link Completable} passed to the consumer
         * @see #with(Consumer)
         */
        public Completable compose(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            whenComplete((r, x) ->
            {
                if (x == null)
                    consumer.accept(completable);
                else
                    completable.failed(x);
            });
            return completable;
        }
    }

    /**
     * Invoke a callback failure, handling any {@link Throwable} thrown
     * by adding the passed {@code failure} as a suppressed with
     * {@link ExceptionUtil#addSuppressedIfNotAssociated(Throwable, Throwable)}.
     * @param callback The callback to fail
     * @param failure The failure
     * @throws RuntimeException If thrown, will have the {@code failure} added as a suppressed.
     */
    private static void failed(Callback callback, Throwable failure)
    {
        try
        {
            callback.failed(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
    }

    /**
     * Invoke two consumers of a failure, handling any {@link Throwable} thrown
     * by adding the passed {@code failure} as a suppressed with
     * {@link ExceptionUtil#addSuppressedIfNotAssociated(Throwable, Throwable)}.
     * @param first The first consumer of a failure
     * @param second The first consumer of a failure
     * @param failure The failure
     * @throws RuntimeException If thrown, will have the {@code failure} added as a suppressed.
     */
    private static void failed(Consumer<Throwable> first, Consumer<Throwable> second,  Throwable failure)
    {
        // This is an improved version of:
        // try
        // {
        //     first.accept(failure);
        // }
        // finally
        // {
        //     second.accept(failure);
        // }
        try
        {
            first.accept(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(failure, t);
        }
        try
        {
            second.accept(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
    }
}
