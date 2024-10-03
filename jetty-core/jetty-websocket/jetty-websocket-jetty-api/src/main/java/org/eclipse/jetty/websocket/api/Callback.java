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

package org.eclipse.jetty.websocket.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * <p>A callback object that handles success/failure events of asynchronous operations.</p>
 * <p>NOTE: This interface is almost identical to {@code org.eclipse.jetty.util.Callback},
 * which however cannot be used in the Jetty WebSocket APIs for web application classloading
 * reasons.</p>
 */
public interface Callback
{
    /**
     * <p>Empty implementation.</p>
     */
    Callback NOOP = new Callback()
    {
    };

    /**
     * <p>Creates a callback from the given success and failure lambdas.</p>
     *
     * @param success the success action to invoke when the callback succeeds
     * @param failure the failure consumer to invoke when the callback fails
     * @return a new callback
     */
    static Callback from(Runnable success, Consumer<Throwable> failure)
    {
        return new Callback()
        {
            @Override
            public void succeed()
            {
                success.run();
            }

            @Override
            public void fail(Throwable x)
            {
                failure.accept(x);
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
        return new Callback()
        {
            @Override
            public void succeed()
            {
                callback.succeed();
                completed.run();
            }

            @Override
            public void fail(Throwable x)
            {
                callback.fail(x);
                completed.run();
            }
        };
    }

    /**
     * <p>Method to invoke to succeed the callback.</p>
     *
     * @see #fail(Throwable)
     */
    default void succeed()
    {
    }

    /**
     * <p>Method to invoke to fail the callback.</p>
     *
     * @param x the failure
     */
    default void fail(Throwable x)
    {
    }

    /**
     * <p>Converts this callback into a {@link BiConsumer} that can be passed
     * to {@link CompletableFuture#whenComplete(BiConsumer)}.</p>
     * <p>When the {@link BiConsumer} is accepted, this callback is completed.</p>
     *
     * @return a {@link BiConsumer} that completes this callback
     * @see Completable#with(Consumer)
     */
    default BiConsumer<? super Void, ? super Throwable> asBiConsumer()
    {
        return (r, x) ->
        {
            if (x == null)
                succeed();
            else
                fail(x);
        };
    }

    /**
     * <p>A {@link Callback} that is also a {@link CompletableFuture}.</p>
     * <p>Applications can pass an instance of this class as a {@link Callback},
     * but also use the {@link CompletableFuture} APIs.
     */
    class Completable extends CompletableFuture<Void> implements Callback
    {
        /**
         * <p>Convenience method that creates a {@link Completable} that
         * is passed to the given consumer and then returned.</p>
         * <p>For example:</p>
         * <pre>{@code
         * Completable.with(completable -> session.sendText("TEXT", completable))
         *     .thenRun(session::demand);
         * }</pre>
         *
         * @param consumer the consumer that receives the completable
         * @return a new completable passed to the consumer
         * @see #compose(Consumer)
         */
        public static Completable with(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            consumer.accept(completable);
            return completable;
        }

        @Override
        public void succeed()
        {
            complete(null);
        }

        @Override
        public void fail(Throwable x)
        {
            completeExceptionally(x);
        }

        /**
         * <p>Returns a new {@link Completable} that, when this {@link Completable}
         * succeeds, is passed to the given consumer and then returned.</p>
         * <p>If this {@link Completable} fails, the new {@link Completable} is
         * also failed, and the consumer is not invoked.</p>
         * <p>For example:</p>
         * <pre>{@code
         * Callback.Completable.with(completable1 -> session.sendPartialText("hello", false, completable1))
         *     .compose(completable2 -> session.sendPartialText(" ", false, completable2))
         *     .compose(completable3 -> session.sendPartialText("world", true, completable3))
         *     .thenRun(session::demand);
         * }</pre>
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
                    completable.fail(x);
            });
            return completable;
        }
    }
}
