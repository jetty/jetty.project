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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
     * A LifeCycle that may take a controlled time to stop.
     * This may be during a {@link Graceful#shutdown()} (eg ContextHandler)
     * or it may be during a {@link org.eclipse.jetty.util.component.LifeCycle#stop()} (eg QueuedThreadPool)
     */
    interface GracefulLifeCycle extends LifeCycle
    {
        void setStopTimeout(long stopTimeout);

        @ManagedAttribute("Time in ms to gracefully shutdown the server")
        long getStopTimeout();
    }

    /**
     * Containers that are {@link GracefulLifeCycle}s and that apply the
     * stop timeout during shutdown in a call to
     * {@link Graceful#shutdown(GracefulContainer)}
     */
    interface GracefulContainer extends Container, GracefulLifeCycle
    {
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

    /**
     * Shutdown a component:<ul>
     *     <li>All contained {@link Graceful} instances are found and their {@link Graceful#shutdown()} method is called.</li>
     *     <li>The {@link GracefulLifeCycle#getStopTimeout()} time is used to limit the time waiting on the {@link Future}s returned
     *     from the shutdown calls</li>
     *     <li>Any uncompleted futures after the time is exhausted are cancelled.</li>
     * </ul>
     *
     * @param component The component to shutdown.
     * @throws Exception If there was a problem in the shutdown.
     */
    static void shutdown(GracefulContainer component) throws Exception
    {
        Logger log = Log.getLogger(component.getClass());

        log.info("Shutdown {} in {}ms", component, component.getStopTimeout());

        if (component.getStopTimeout() <= 0)
            return;

        MultiException mex = null;

        // tell the graceful handlers that we are shutting down
        List<Graceful> gracefuls = new ArrayList<>();
        if (component instanceof Graceful)
            gracefuls.add((Graceful)component);
        component.getContainedBeans(Graceful.class).forEach(gracefuls::add);

        if (log.isDebugEnabled())
            gracefuls.forEach(g -> log.debug("graceful {}", g));

        List<Future<Void>> futures = gracefuls.stream().map(Graceful::shutdown).collect(Collectors.toList());

        if (log.isDebugEnabled())
            futures.forEach(f -> log.debug("future {}", f));

        // Wait for all futures with a reducing time budget
        long stopTimeout = component.getStopTimeout();
        long stopBy = System.currentTimeMillis() + stopTimeout;
        if (log.isDebugEnabled())
            log.debug("Graceful shutdown {} by {}", component, new Date(stopBy));

        // Wait for shutdowns
        for (Future<Void> future : futures)
        {
            try
            {
                if (!future.isDone())
                    future.get(Math.max(1L, stopBy - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (log.isDebugEnabled())
                    log.debug("done {}", future);
            }
            catch (TimeoutException e)
            {
                if (mex == null)
                    mex = new MultiException();
                mex.add(new Exception("Failed to gracefully stop " + future, e));
            }
            catch (Throwable e)
            {
                // If the future is also a callback, fail it here (rather than cancel) so we can capture the exception
                if (future instanceof Callback && !future.isDone())
                    ((Callback)future).failed(e);
                if (mex == null)
                    mex = new MultiException();
                mex.add(e);
            }
        }

        // Cancel any shutdowns not done
        for (Future<Void> future : futures)
        {
            if (!future.isDone())
                future.cancel(true);
        }

        if (mex != null)
            mex.ifExceptionThrow();

        if (log.isDebugEnabled())
            log.debug("Graceful shutdown {}", component);
    }
}
