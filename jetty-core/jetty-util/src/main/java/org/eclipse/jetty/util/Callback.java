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
     * Instance of Adapter that can be used when the callback methods need an empty
     * implementation without incurring in the cost of allocating a new Adapter object.
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
     * @param invocation whether the callback is blocking
     * @return a callback that when completed, completes the given CompletableFuture
     */
    static Callback from(CompletableFuture<?> completable, InvocationType invocation)
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
                return invocation;
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
     * Creates a nested callback that runs completed after
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run after the nested callback is completed
     * @return a new callback.
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
     * Creates a nested callback that runs completed after
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run after the nested callback is completed
     * @return a new callback.
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
                try
                {
                    callback.failed(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(x, t);
                }
                try
                {
                    completed.accept(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
            }
        };
    }

    /**
     * Creates a nested callback that runs completed before
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run before the nested callback is completed. Any exceptions thrown
     * from completed will result in a callback failure.
     * @return a new callback.
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
                    callback.succeeded();
                    completed.run();
                }
                catch (Throwable t)
                {
                    Callback.failed(callback, t);
                }
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    completed.run();
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(x, t);
                }
                Callback.failed(callback, x);
            }
        };
    }

    /**
     * Creates a nested callback which always fails the nested callback on completion.
     *
     * @param callback The nested callback
     * @param cause The cause to fail the nested callback, if the new callback is failed the reason
     * will be added to this cause as a suppressed exception.
     * @return a new callback.
     */
    static Callback from(Callback callback, Throwable cause)
    {
                    callback.succeeded();
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                callback.failed(cause);
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    cause.addSuppressed(x);
                    callback.failed(cause);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
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
            try
            {
                try
                {
                    callback.failed(x);
                }
                finally
                {
                    completed();
                }
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
            return callback.getInvocationType();
        }

        @Override
        public String toString()
        {
            return "%s@%x:%s".formatted(getClass().getSimpleName(), hashCode(), callback);
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
                try
                {
                    cb1.failed(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(x, t);
                }

                Callback.failed(cb2, x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.combine(Invocable.getInvocationType(cb1), Invocable.getInvocationType(cb2));
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
                    Callback.failed(callback, x);
                    super.failed(x);
                }
            };
        }

        private final InvocationType invocation;

        public Completable()
        {
            this(Invocable.InvocationType.NON_BLOCKING);
        }

        public Completable(InvocationType invocation)
        {
            this.invocation = invocation;
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
            return invocation;
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
    static void failed(Callback callback, Throwable failure)
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
}
