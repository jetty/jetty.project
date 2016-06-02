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

import java.util.concurrent.CompletableFuture;

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

    class Nested implements Callback
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

        @Override
        public void succeeded()
        {
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return callback.getInvocationType();
        }
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
