//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Provides a {@link Callback} that can block the thread
 * while waiting to be completed.  The block occurs in the
 * auto-closing of the callback.
 * <p>
 * A typical usage pattern is:
 * <pre>
 * void someBlockingCall(Object... args) throws IOException
 * {
 *     try(BlockingCallback blocker = new BlockingCallback())
 *     {
 *         someAsyncCall(args, blocker);
 *     }
 * }
 * </pre>
 * @see SharedBlockingCallback
 */
public class BlockingCallback implements AutoCloseable, Callback, Runnable
{
    // TODO factor out BlockingRunnable

    // TODO perhaps move all variations to be inners of a single Blocker class that includes the sharable, as well as
    //      these implementations.
    private static final Throwable SUCCEEDED = new Throwable()
    {
        @Override
        public synchronized Throwable fillInStackTrace()
        {
            return this;
        }
    };

    private final CompletableFuture<Throwable> _future = new CompletableFuture<>();

    @Override
    public void close() throws IOException
    {
        Throwable result;
        try
        {
            result = _future.get();
        }
        catch (Throwable t)
        {
            result = t;
        }
        if (result == SUCCEEDED)
            return;
        throw IO.rethrow(result);
    }

    @Override
    public void run()
    {
        succeeded();
    }

    @Override
    public void succeeded()
    {
        _future.complete(SUCCEEDED);
    }

    @Override
    public void failed(Throwable x)
    {
        _future.complete(x == null ? new Throwable() : x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }
}
