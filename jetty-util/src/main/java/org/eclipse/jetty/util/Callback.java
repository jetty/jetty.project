//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
    };

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
     * Create a callback from the passed success and failure
     *
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails
     * @return a new Callback
     */
    static Callback from(Runnable success, Consumer<Throwable> failure)
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
        };
    }

    /**
     * Creaste a callback that runs completed when it succeeds or fails
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
     * Create a nested callback that runs completed after
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
     * Create a nested callback that runs completed before
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

    class Completing implements Callback
    {
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

    interface InvocableCallback extends Invocable, Callback
    {
    }

    static Callback combine(Callback cb1, Callback cb2)
    {
        if (cb1 == null || cb1 == cb2)
            return cb2;
        if (cb2 == null)
            return cb1;

        return new InvocableCallback()
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
