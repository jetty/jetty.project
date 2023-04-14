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
import java.util.function.Consumer;

/**
 * Callback for Write events.
 * <p>
 * NOTE: We don't expose org.eclipse.jetty.util.Callback here as that would complicate matters with the WebAppContext's classloader isolation.
 */
public interface Callback
{
    Callback NOOP = new Callback()
    {
    };

    /**
     * <p>
     * Callback invoked when the write fails.
     * </p>
     *
     * @param x the reason for the write failure
     */
    default void fail(Throwable x)
    {
    }

    /**
     * <p>
     * Callback invoked when the write succeeds.
     * </p>
     *
     * @see #fail(Throwable)
     */
    default void succeed()
    {
    }

    class Completable extends CompletableFuture<Void> implements Callback
    {
        public static Completable with(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            consumer.accept(completable);
            return completable;
        }

        private Completable()
        {
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

        public Completable compose(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            thenAccept(ignored -> consumer.accept(completable));
            return completable;
        }
    }
}
