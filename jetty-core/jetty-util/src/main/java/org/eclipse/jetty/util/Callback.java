//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
    static Callback NOOP = new Callback()
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
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
                completable.completeExceptionally(x);
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
                failure.accept(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
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
        return new Completing()
        {
            public void completed()
            {
                completed.run();
            }
        };
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
        return new Completing(invocationType)
        {
            @Override
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
                    callback.failed(t);
                }
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
                    x.addSuppressed(t);
                }
                callback.failed(x);
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
            public void failed(Throwable x)
            {
                cause.addSuppressed(x);
                callback.failed(cause);
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
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                callback1.succeeded();
                callback2.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                callback1.failed(x);
                callback2.failed(x);
            }
        };
    }

    /**
     * <p>A Callback implementation that calls the {@link #completed()} method when it either succeeds or fails.</p>
     */
    class Completing implements Callback
    {
        private final InvocationType invocationType;

        public Completing()
        {
            this(InvocationType.BLOCKING);
        }

        public Completing(InvocationType invocationType)
        {
            this.invocationType = invocationType;
        }

        @Override
        public void succeeded()
        {
            completed();
        }

        @Override
        public void failed(Throwable x)
        {
            completed();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return invocationType;
        }

        public void completed()
        {
        }
    }

    /**
     * Nested Completing Callback that completes after
     * completing the nested callback
     */
    class Nested extends Completing
    {
        private final Callback callback;

        public Nested(Callback callback)
        {
            this.callback = callback;
        }

        public Nested(Nested nested)
        {
            this.callback = nested.callback;
        }

        public Callback getCallback()
        {
            return callback;
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
                callback.failed(x);
            }
            finally
            {
                completed();
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return callback.getInvocationType();
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
                    if (x != t)
                        x.addSuppressed(t);
                }
                finally
                {
                    cb2.failed(x);
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.combine(Invocable.getInvocationType(cb1), Invocable.getInvocationType(cb2));
            }
        };
    }

    /**
     * <p>A CompletableFuture that is also a Callback.</p>
     */
    class Completable extends CompletableFuture<Void> implements Callback
    {
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
                    callback.failed(x);
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
    }
}
