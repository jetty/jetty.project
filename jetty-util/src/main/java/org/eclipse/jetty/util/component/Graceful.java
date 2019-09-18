//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.FutureCallback;

/* A Lifecycle that can be gracefully shutdown.
 */
public interface Graceful
{
    Phase getShutdownPhase();

    Future<Void> shutdown();

    boolean isShutdown();

    enum Phase
    {
        UNAVAILABLE,  // Make the component unavailable for future tasks (eg stop acceptor)
        GRACEFUL,     // Gracefully wait for current tasks to complete (eg request complete)
        CLEANUP       // Time limited Shutdown operations (eg connection close)
    }

    abstract class GracefulFuture<T extends Future<Void>> implements Graceful
    {
        final AtomicReference<T> _future = new AtomicReference<>();
        final Phase _phase;

        protected abstract T newFuture();

        protected abstract void doShutdown(T future);

        public GracefulFuture()
        {
            this(Phase.GRACEFUL);
        }

        public GracefulFuture(Phase phase)
        {
            _phase = phase;
        }

        @Override
        public Phase getShutdownPhase()
        {
            return _phase;
        }

        @Override
        public Future<Void> shutdown()
        {
            T future = _future.get();
            if (future == null)
            {
                future = newFuture();
                if (_future.compareAndSet(null, future))
                    doShutdown(future);
                else
                    future = _future.get();
            }
            return future;
        }

        @Override
        public boolean isShutdown()
        {
            T future = _future.get();
            return future != null;
        }

        public T getFuture()
        {
            return _future.get();
        }

        public void reset()
        {
            _future.set(null);
        }

        @Override
        public String toString()
        {
            T future = _future.get();
            return String.format("%s@%x{%s}", getClass(), hashCode(), future == null ? null : future.isDone() ? "Shutdown" : "ShuttingDown");
        }
    }

    class Shutdown extends GracefulFuture<FutureCallback>
    {
        protected FutureCallback newFuture()
        {
            return new FutureCallback();
        }

        protected void doShutdown(FutureCallback future)
        {
            future.succeeded();
        }
    }
}
