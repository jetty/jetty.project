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

package org.eclipse.jetty.util.component;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;

/**
 * <p>Jetty components that wish to be part of a Graceful shutdown implement this interface so that
 * the {@link Graceful#shutdown()} method will be called to initiate a shutdown.    Shutdown operations
 * can fall into the following categories:</p>
 * <ul>
 *     <li>Preventing new load from being accepted (eg connectors stop accepting connections)</li>
 *     <li>Preventing existing load expanding (eg stopping existing connections accepting new requests)</li>
 *     <li>Waiting for existing load to complete (eg waiting for active request count to reduce to 0)</li>
 *     <li>Performing cleanup operations that may take time (eg closing an SSL connection)</li>
 * </ul>
 * <p>The {@link Future} returned by the the shutdown call will be completed to indicate the shutdown operation is completed.
 * Some shutdown operations may be instantaneous and always return a completed future.
 * </p><p>
 * Graceful shutdown is typically orchestrated by the doStop methods of Server or ContextHandler (for a full or partial
 * shutdown respectively).
 * </p>
 */
public interface Graceful
{
    Future<Void> shutdown();

    boolean isShutdown();

    /**
     * A utility Graceful that uses a {@link FutureCallback} to indicate if shutdown is completed.
     * By default the {@link FutureCallback} is returned as already completed, but the {@link #newShutdownCallback()} method
     * can be overloaded to return a non-completed callback that will require a {@link Callback#succeeded()} or
     * {@link Callback#failed(Throwable)} call to be completed.
     */
    class Shutdown implements Graceful
    {
        private final AtomicReference<FutureCallback> _shutdown = new AtomicReference<>();

        protected FutureCallback newShutdownCallback()
        {
            return FutureCallback.SUCCEEDED;
        }

        @Override
        public Future<Void> shutdown()
        {
            return _shutdown.updateAndGet(fcb -> fcb == null ? newShutdownCallback() : fcb);
        }

        @Override
        public boolean isShutdown()
        {
            return _shutdown.get() != null;
        }

        public void cancel()
        {
            FutureCallback shutdown = _shutdown.getAndSet(null);
            if (shutdown != null && !shutdown.isDone())
                shutdown.cancel(true);
        }

        public FutureCallback get()
        {
            return _shutdown.get();
        }
    }
}
