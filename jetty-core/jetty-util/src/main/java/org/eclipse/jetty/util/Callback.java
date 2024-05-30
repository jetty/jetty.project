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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicMarkableReference;
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
     *
     */
    default boolean cancel(Throwable x)
    {
        failed(x);
        return true;
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
            public boolean cancel(Throwable x)
            {
                return callback.cancel(x);
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(callback::failed, completed, x);
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
                    completed.run();
                    callback.succeeded();
                }
                catch (Throwable t)
                {
                    Callback.failed(callback, t);
                }
            }

            @Override
            public boolean cancel(Throwable x)
            {
                return callback.cancel(x);
            }

            private void completed(Throwable ignored)
            {
                completed.run();
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(this::completed, callback::failed, x);
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
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                callback.failed(cause);
            }

            @Override
            public boolean cancel(Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                return callback.cancel(cause);
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                Callback.failed(callback, cause);
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
        public boolean cancel(Throwable x)
        {
            return callback.cancel(x);
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
            public boolean cancel(Throwable x)
            {
                return Callback.cancel(cb1, this, x);
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(cb1::failed, cb2::failed, x);
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
                public boolean cancel(boolean mayInterruptIfRunning)
                {
                    return super.cancel(mayInterruptIfRunning) && CancelableCallback.cancel(callback, null);
                }

                @Override
                public void failed(Throwable x)
                {
                    Callback.failed(callback::failed, super::failed, x);
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
    private static boolean cancel(Callback first, Callback second,  Throwable failure)
    {
        boolean cancelled = false;
        try
        {
            cancelled = first.cancel(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(failure, t);
        }
        try
        {
            cancelled |= second.cancel(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
        return cancelled;
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

    /**
     * A Callback that can be cancelled.
     * The callback is cancelled when it is known that it cannot be completed successfully, but a scheduled callback to
     * {@link #succeeded()} or {@link #failed(Throwable)} cannot be aborted.
     * The following methods can be extended:
     * <ul>
     *     <li>{@link #onSuccess()} will be called by a call to {@link #succeeded()} if the Callback has not already been
     *     {@link #cancel(Throwable) cancelled}.</li>
     *     <li>{@link #onFailure(Throwable)} will be called by a call to {@link #failed(Throwable)} if the Callback has not
     *     already been {@link #cancel(Throwable) cancelled}.</li>
     *     <li>{@link #onComplete(Throwable)} will always be called once either {@link #succeeded()} or
     *     {@link #failed(Throwable)} has been called.  If passed {@link Throwable} will be null only if the callback has
     *     neither been {@link #cancel(Throwable) cancelled} nor {@link #failed(Throwable) failed}.</li>
     * </ul>
     */
    class CancelableCallback implements Callback
    {
        private static final Throwable SUCCEEDED = new StaticException("Succeeded");
        AtomicMarkableReference<Throwable> _completion = new AtomicMarkableReference<>(null, false);

        /**
         * Cancel or fail a callback.
         * @param callback The {@link Callback} to {@link #cancel(Throwable)} if it is an instance of {@code CancelableCallback},
         *                 else it is {@link #failed(Throwable) failed}.
         * @param cause The cause of the cancellation
         */
        static boolean cancel(Callback callback, Throwable cause)
        {
            if (callback instanceof CancelableCallback cancelableCallback)
                return cancelableCallback.cancel(cause);

            callback.failed(cause);
            return true;
        }

        /**
         * Cancel the callback if it has not already been completed.
         * The {@link #onFailure(Throwable)} method will be called directly by this call, then
         * the {@link #onComplete(Throwable)} method will be called only once the {@link #succeeded()} or
         * {@link #failed(Throwable)} methods are called.
         * @param cause The cause of the cancellation
         * @return true if the callback was cancelled
         */
        public boolean cancel(Throwable cause)
        {
            if (cause == null)
                cause = new CancellationException();
            if (_completion.compareAndSet(null, cause, false, false))
            {
                onFailure(cause);
                return true;
            }
            return false;
        }

        @Override
        public void failed(Throwable failure)
        {
            if (failure == null)
                failure = new Exception();
            while (true)
            {
                if (_completion.compareAndSet(null, failure, false, true))
                {
                    try
                    {
                        onFailure(failure);
                    }
                    finally
                    {
                        onComplete(null);
                    }
                    return;
                }

                if (_completion.isMarked())
                    return;

                Throwable cause = _completion.getReference();
                ExceptionUtil.addSuppressedIfNotAssociated(cause, failure);
                if (_completion.compareAndSet(cause, cause, false, true))
                {
                    onComplete(cause);
                    return;
                }
            }
        }

        @Override
        public void succeeded()
        {
            while (true)
            {
                if (_completion.compareAndSet(null, SUCCEEDED, false, true))
                {
                    try
                    {
                        onSuccess();
                    }
                    finally
                    {
                        onComplete(null);
                    }
                    return;
                }

                if (_completion.isMarked())
                    return;

                Throwable cause = _completion.getReference();
                if (_completion.compareAndSet(cause, cause, false, true))
                {
                    onComplete(cause);
                    return;
                }
            }
        }

        /**
         * Called when the callback has been {@link #succeeded() succeeded} but not {@link #cancel(Throwable) cancelled}.
         * The {@link #onComplete(Throwable)} method will be called with a {@code null} argument after this call.
         * Typically, this method is implement to act on the success.  It can release or reuse any resources that may have
         * been in use by the scheduled operation, but it should defer that release or reuse to the subsequent call to
         * {@link #onComplete(Throwable)} to avoid double releasing.
         */
        protected void onSuccess()
        {

        }

        /**
         * Called when the callback has either been {@link #failed(Throwable) failed} or {@link #cancel(Throwable) cancelled}.
         * The {@link #onComplete(Throwable)} method will ultimately be called, but only once the callback has been
         * {@link #succeeded() succeeded} or {@link #failed(Throwable)}.
         * Typically, this method is implemented to act on the failure, but it cannot release or reuse any resources that may
         * be in use by the schedule operation.
         * @param cause The cause of the failure or cancellation
         */
        protected void onFailure(Throwable cause)
        {

        }

        /**
         * Called once the callback has been either {@link #succeeded() succeeded} or {@link #failed(Throwable)}.
         * Typically, this method is implemented to release resources that may be used by the scheduled operation.
         * @param cause The cause of the failure or cancellation
         */
        protected void onComplete(Throwable cause)
        {

        }
    }
}
