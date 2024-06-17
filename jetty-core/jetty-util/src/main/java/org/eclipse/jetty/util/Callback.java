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
import java.util.concurrent.atomic.AtomicReference;
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
     * <p>Cancel the callback, prior to either {@link #succeeded()} or {@link #failed(Throwable)} being called.
     * The operation to which the {@code Callback} has been passed must ultimately call either {@link #succeeded()} or
     * {@link #failed(Throwable)}</p>
     *
     * @param cause the reason for the operation failure
     * @return {@code true} if the call to abort was prior to a call to either {@link #succeeded()}, {@link #failed(Throwable)}
     * or another call to {@code abort(Throwable)}.
     * @see Abstract
     */
    default boolean abort(Throwable cause)
    {
        failed(cause);
        return true;
    }

    /**
     * <p>Callback invoked when the operation fails.</p>
     *
     * @param cause the reason for the operation failure
     */
    default void failed(Throwable cause)
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

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), completable, invocation);
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
     * @param failure Called when the callback fails or has been aborted and completed
     * @return a new Callback
     */
    static Callback from(InvocationType invocationType, Runnable success, Consumer<Throwable> failure)
    {
        return new Abstract()
        {
            @Override
            public void onCompleted(Throwable causeOrNull)
            {
                try
                {
                    if (causeOrNull == null)
                        success.run();
                    else
                        failure.accept(causeOrNull);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, causeOrNull);
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
                return "Callback.from@%x{%s, %s,%s}".formatted(hashCode(), invocationType, success, failure);
            }
        };
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails or has been aborted
     * @param completed Called when the callback fails or has been aborted and completed
     * @return a new Callback
     */
    static Callback from(Runnable success, Consumer<Throwable> failure, Consumer<Throwable> completed)
    {
        return from(Invocable.getInvocationType(success), success, failure, completed);
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param invocationType the Callback invocation type
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails or has been aborted
     * @param completed Called when the callback fails or has been aborted and completed
     * @return a new Callback
     */
    static Callback from(InvocationType invocationType, Runnable success, Consumer<Throwable> failure, Consumer<Throwable> completed)
    {
        return new Abstract()
        {
            @Override
            protected void onSucceeded()
            {
                success.run();
            }

            @Override
            protected void onFailed(Throwable cause)
            {
                ExceptionUtil.call(cause, failure);
            }

            @Override
            protected void onCompleted(Throwable causeOrNull)
            {
                ExceptionUtil.call(causeOrNull, completed);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s,%s,%s}".formatted(hashCode(), invocationType, success, failure, completed);
            }
        };
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param success Called when the callback succeeds
     * @param abort Called when the callback jas been failed
     * @param failure Called when the callback fails or has been aborted
     * @param completed Called when the callback fails or has been aborted and completed
     * @return a new Callback
     */
    static Callback from(Runnable success, Consumer<Throwable> abort, Consumer<Throwable> failure, Consumer<Throwable> completed)
    {
        return from(Invocable.getInvocationType(success), success, abort, failure, completed);
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param invocationType the Callback invocation type
     * @param success Called when the callback succeeds
     * @param abort Called when the callback jas been failed
     * @param failure Called when the callback fails or has been aborted
     * @param completed Called when the callback fails or has been aborted and completed
     * @return a new Callback
     */
    static Callback from(InvocationType invocationType, Runnable success, Consumer<Throwable> abort, Consumer<Throwable> failure, Consumer<Throwable> completed)
    {
        return new Abstract()
        {
            @Override
            protected void onSucceeded()
            {
                success.run();
            }

            @Override
            protected void onAborted(Throwable cause)
            {
                ExceptionUtil.call(cause, abort);
            }

            @Override
            protected void onFailed(Throwable cause)
            {
                ExceptionUtil.call(cause, failure);
            }

            @Override
            protected void onCompleted(Throwable causeOrNull)
            {
                ExceptionUtil.call(causeOrNull, completed);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s,%s,%s,%s}".formatted(hashCode(), invocationType, success, abort, failure, completed);
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
        return new Abstract()
        {
            @Override
            public void onCompleted(Throwable causeOrNull)
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
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), invocationType, completed);
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
            @Override
            public void onCompleted(Throwable causeOrNull)
            {
                ExceptionUtil.callAndThen(causeOrNull, super::onCompleted, completed);
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), callback, completed);
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
        return new Nested(callback)
        {
            @Override
            protected void onCompleted(Throwable cause)
            {
                ExceptionUtil.callAndThen(cause, super::onCompleted, completed);
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), callback, completed);
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
                    ExceptionUtil.call(t, callback::failed);
                }
            }

            @Override
            public boolean abort(Throwable cause)
            {
                return callback.abort(cause);
            }

            private void completed(Throwable ignored)
            {
                completed.run();
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.callAndThen(x, this::completed, callback::failed);
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), completed, callback);
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
            public boolean abort(Throwable abortCause)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, abortCause);
                return callback.abort(cause);
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                ExceptionUtil.call(cause, callback::failed);
            }

            @Override
            public String toString()
            {
                return "Callback.from@%x{%s,%s}".formatted(hashCode(), callback, cause);
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
     * @deprecated use {@link Abstract}
     */
    @Deprecated (forRemoval = true, since = "12.0.11")
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
     * A Callback implementation that calls the {@link #onCompleted(Throwable)} method when it either succeeds or fails.
     * If the callback is aborted, then {@link #onAborted(Throwable)} is called, but the
     * {@link #onCompleted(Throwable)} methods is not called until either {@link #succeeded()} or {@link #failed(Throwable)}
     * are called.  Valid sequences of calls are: <ul>
     *     <li>{@link #succeeded()} -> {@link #onSucceeded()} -> {@link #onCompleted(Throwable)}</li>
     *     <li>{@link #failed(Throwable)} -> {@link #onFailed(Throwable)} -> {@link #onCompleted(Throwable)}</li>
     *     <li>{@link #abort(Throwable)} -> {@link #onAborted(Throwable)} -> {@link #onFailed(Throwable)}, {@link #succeeded()} -> {@link #onCompleted(Throwable)}</li>
     *     <li>{@link #abort(Throwable)} -> {@link #onAborted(Throwable)} -> {@link #onFailed(Throwable)}, {@link #failed(Throwable)} -> {@link #onCompleted(Throwable)}</li>
     * </ul>
     */
    class Abstract implements Callback
    {
        private static final State SUCCEEDED = new State(null);

        private final AtomicReference<State> _state = new AtomicReference<>(null);

        @Override
        public void succeeded()
        {
            // Is this a direct success?
            if (_state.compareAndSet(null, SUCCEEDED))
            {
                Throwable cause = null;
                try
                {
                    onSucceeded();
                }
                catch (Throwable t)
                {
                    cause = t;
                }
                finally
                {
                    onCompleted(cause);
                }
                return;
            }

            // Are we aborted?
            State state = _state.get();
            if (state instanceof Aborted aborted)
            {
                // If the abort notifications are still running, then they will complete for us
                if (aborted.completed.compareAndSet(null, Boolean.TRUE))
                    return;

                // abort notifications were complete, so we do the completions
                if (aborted.completed.compareAndSet(Boolean.FALSE, Boolean.TRUE))
                    ExceptionUtil.call(state.causeOrNull, this::onCompleted);
            }
        }

        @Override
        public void failed(Throwable cause)
        {
            cause = Objects.requireNonNullElseGet(cause, Exception::new);

            // Is this a direct failure?
            if (_state.compareAndSet(null, new State(cause)))
            {
                ExceptionUtil.callAndThen(cause, this::onFailed, this::onCompleted);
                return;
            }

            // Are we aborted?
            State state = _state.get();
            if (state instanceof Aborted aborted)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(aborted.causeOrNull, cause);

                // If the abort notifications are still running, then they will complete for us
                if (aborted.completed.compareAndSet(null, Boolean.TRUE))
                    return;

                // abort notifications were complete, so we do the completions
                if (aborted.completed.compareAndSet(Boolean.FALSE, Boolean.TRUE))
                    ExceptionUtil.call(state.causeOrNull, this::onCompleted);
            }
        }

        /**
         * Abort the callback if it has not already been completed.
         * The {@link #onAborted(Throwable)} and {@link #onFailed(Throwable)} methods will be called, then the
         * {@link #onCompleted(Throwable)} will be called only once the {@link #succeeded()} or
         * {@link #failed(Throwable)} methods are called.
         * @param cause The cause of the abort
         * @return true if the callback was aborted
         */
        @Override
        public boolean abort(Throwable cause)
        {
            cause = Objects.requireNonNullElseGet(cause, CancellationException::new);

            Aborted aborted = new Aborted(cause);

            // If we can directly abort, then we are aborting
            if (_state.compareAndSet(null, aborted))
            {
                // We are aborting, so notify
                ExceptionUtil.callAndThen(cause, this::onAborted, this::onFailed);

                // if we can set completed then we were not succeeded or failed during notification
                if (aborted.completed.compareAndSet(null, Boolean.FALSE))
                    return true;

                // We were succeeded or failed during notification, so we have to complete
                ExceptionUtil.call(cause, this::onCompleted);
                return true;
            }

            // Either we were already complete or somebody else is aborting
            ExceptionUtil.addSuppressedIfNotAssociated(_state.get().causeOrNull, cause);
            return false;
        }

        /**
         * Called when the callback has been {@link #succeeded() succeeded} and not {@link #abort(Throwable) aborted}.
         * The {@link #onCompleted(Throwable)} method will be also be called after this call.
         * Typically, this method is implement to act on the success.  It can release or reuse any resources that may have
         * been in use by the scheduled operation, but it may defer that release or reuse to the subsequent call to
         * {@link #onCompleted(Throwable)} to avoid double releasing.
         */
        protected void onSucceeded()
        {
        }

        /**
         * Called when the callback has been {@link #abort(Throwable) aborted}.
         * The {@link #onFailed(Throwable)} method will also be called immediately, but then once the callback has been
         * {@link #succeeded() succeeded} or {@link #failed(Throwable)}, and the {@link #onCompleted(Throwable)} method will be also be called.
         * Typically, this method is implemented to act on the failure, but it should not release or reuse any resources that may
         * be in use by the schedule operation.
         * @param cause The cause of the abort
         */
        protected void onAborted(Throwable cause)
        {
        }

        /**
         * Called when the callback has either been {@link #abort(Throwable) aborted} or {@link #failed(Throwable)}.
         * The {@link #onCompleted(Throwable)} method will ultimately be called, but only once the callback has been
         * {@link #succeeded() succeeded} or {@link #failed(Throwable)}, and then the {@link #onCompleted(Throwable)} method will be also be called.
         * Typically, this method is implemented to act on the failure, but it should not release or reuse any resources that may
         * be in use by the schedule operation.
         * @param cause The cause of the failure
         */
        protected void onFailed(Throwable cause)
        {
        }

        /**
         * Called once the callback has been either {@link #succeeded() succeeded} or {@link #failed(Throwable)}.
         * Typically, this method is implemented to release resources that may be used by the scheduled operation.
         * @param causeOrNull {@code null} if successful, else the cause of any {@link #abort(Throwable) abort} or
         * {@link #failed(Throwable) failure}
         */
        protected void onCompleted(Throwable causeOrNull)
        {
        }

        private static class State
        {
            final Throwable causeOrNull;

            protected State(Throwable causeOrNull)
            {
                this.causeOrNull = causeOrNull;
            }
        }

        private static class Aborted extends State
        {
            private final AtomicReference<Boolean> completed = new AtomicReference<>(null);

            private Aborted(Throwable cause)
            {
                super(cause);
            }
        }
    }

    /*
     * A Callback that wraps another Callback
     */
    class Wrapper implements Callback
    {
        private final Callback callback;

        public Wrapper(Callback callback)
        {
            this.callback = Objects.requireNonNull(callback);
        }

        public Callback getCallback()
        {
            return callback;
        }

        @Override
        public boolean abort(Throwable cause)
        {
            return callback.abort(cause);
        }

        @Override
        public void failed(Throwable cause)
        {
            callback.failed(cause);
        }

        @Override
        public void succeeded()
        {
            callback.succeeded();
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

    /**
     * Nested Completing Callback that completes after
     * completing the nested callback
     */
    class Nested extends Abstract
    {
        private final Callback callback;

        public Nested(Callback callback)
        {
            this.callback = Objects.requireNonNull(callback);
        }

        @Override
        protected void onAborted(Throwable cause)
        {
            callback.abort(cause);
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            if (causeOrNull == null)
                callback.succeeded();
            else
                callback.failed(causeOrNull);
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
            public boolean abort(Throwable cause)
            {
                boolean c1 = cb1.abort(cause);
                boolean c2 = cb2.abort(cause);
                return c1 || c2;
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.callAndThen(x, cb1::failed, cb2::failed);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.combine(Invocable.getInvocationType(cb1), Invocable.getInvocationType(cb2));
            }

            @Override
            public String toString()
            {
                return "Callback.combine@%x{%s,%s}".formatted(hashCode(), cb1, cb2);
            }
        };
    }

    /**
     * <p>A {@link CompletableFuture} that is also a {@link Callback}.</p>
     * @deprecated TODO This should either be a Callback that wraps a CompletableFuture OR a CompletableFuture that
     *                  wraps a Callback, but not both.
     */
    @Deprecated
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
                public boolean abort(Throwable cause)
                {
                    return callback.abort(cause) && super.abort(cause);
                }

                @Override
                public void failed(Throwable x)
                {
                    ExceptionUtil.callAndThen(x, callback::failed, super::failed);
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
        public boolean abort(Throwable cause)
        {
            return cancel(false);
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
}
