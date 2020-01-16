//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.component;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

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
    /**
     * Shutdown the component. When this method returns, the component should not accept any new load.
     * @return A future that is completed once all load on the component is completed
     */
    Future<Void> shutdown();

    /**
     * @return True if {@link #shutdown()} has been called.
     */
    boolean isShutdown();

    /**
     * A Graceful LifeCycle that may take a controlled time to stop.
     */
    interface LifeCycle extends org.eclipse.jetty.util.component.LifeCycle
    {
        void setStopTimeout(long stopTimeout);

        @ManagedAttribute("Time in ms to gracefully shutdown the server")
        long getStopTimeout();
    }

    /**
     * A utility class to assist implementing the Graceful interface.
     * The {@link #isShutdownDone()} method should be implemented to check if the future
     * returned by {@link #shutdown()} should be completed or not.  The {@link #check()}
     * method should be called when any state is changed which may complete the shutdown.
     */
    abstract class Shutdown implements Graceful
    {
        final Object _component;
        final AtomicReference<FutureCallback> _done = new AtomicReference<>();

        protected Shutdown(Object component)
        {
            _component = component;
        }

        @Override
        public Future<Void> shutdown()
        {
            if (_done.get() == null)
                _done.compareAndSet(null, new FutureCallback()
                {
                    @Override
                    public String toString()
                    {
                        return String.format("Shutdown<%s>@%x", _component, hashCode());
                    }
                });
            FutureCallback done = _done.get();
            check();
            return done;
        }

        @Override
        public boolean isShutdown()
        {
            return _done.get() != null;
        }

        /**
         * This method should be called whenever the components state has been updated.
         * If {@link #shutdown()} has been called, then {@link #isShutdownDone()} is called
         * by this method and if it returns true then the {@link Future} returned by
         * {@link #shutdown()} is completed.
         */
        public void check()
        {
            FutureCallback done = _done.get();
            if (done != null && isShutdownDone())
                done.succeeded();
        }

        /**
         * @return True if the component is shutdown and has no remaining load.
         */
        public abstract boolean isShutdownDone();
    }
}
