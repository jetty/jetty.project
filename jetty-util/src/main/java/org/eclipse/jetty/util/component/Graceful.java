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

package org.eclipse.jetty.util.component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The {@link CompletableFuture} returned by the the shutdown call will be completed to indicate the
 * shutdown operation is completed.
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
    CompletableFuture<Void> shutdown();

    /**
     * @return True if {@link #shutdown()} has been called.
     */
    boolean isShutdown();

    /**
     * A utility class to assist implementing the Graceful interface.
     * The {@link #isShutdownDone()} method should be implemented to check if the {@link CompletableFuture}
     * returned by {@link #shutdown()} should be completed or not.  The {@link #check()}
     * method should be called when any state is changed which may complete the shutdown.
     */
    abstract class Shutdown implements Graceful
    {
        final Object _component;
        final AtomicReference<CompletableFuture<Void>> _done = new AtomicReference<>();

        protected Shutdown(Object component)
        {
            _component = component;
        }

        @Override
        public CompletableFuture<Void> shutdown()
        {
            if (_done.get() == null)
            {
                _done.compareAndSet(null, new CompletableFuture<>()
                {
                    @Override
                    public String toString()
                    {
                        return String.format("Shutdown<%s>@%x", _component, hashCode());
                    }
                });
            }
            CompletableFuture<Void> done = _done.get();
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
            CompletableFuture<Void> done = _done.get();
            if (done != null && isShutdownDone())
                done.complete(null);
        }

        public void cancel()
        {
            CompletableFuture<Void> done = _done.get();
            if (done != null && !done.isDone())
                done.cancel(true);

            _done.set(null);
        }

        /**
         * @return True if the component is shutdown and has no remaining load.
         */
        public abstract boolean isShutdownDone();
    }

    /**
     * Utility method to shutdown all Gracefuls within a container.
     * @param component The container in which to look for {@link Graceful}s
     * @return A {@link CompletableFuture } that is complete once all returns from {@link Graceful#shutdown()}
     * of the contained {@link Graceful}s are complete.
     */
    static CompletableFuture<Void> shutdown(Container component)
    {
        Logger log = LoggerFactory.getLogger(component.getClass());

        log.info("Shutdown {}", component);

        // tell the graceful handlers that we are shutting down
        List<Graceful> gracefuls = new ArrayList<>();
        if (component instanceof Graceful)
            gracefuls.add((Graceful)component);
        gracefuls.addAll(component.getContainedBeans(Graceful.class));

        if (log.isDebugEnabled())
            gracefuls.forEach(g -> log.debug("graceful {}", g));

        return CompletableFuture.allOf(gracefuls.stream().map(Graceful::shutdown).toArray(CompletableFuture[]::new));
    }

    /**
     * Utility method to execute a {@link ThrowingRunnable} in a new daemon thread and
     * be notified of the result in a {@link CompletableFuture}.
     * @param runnable the ThrowingRunnable to run.
     * @return the CompletableFuture to be notified when the runnable either completes or fails.
     */
    static CompletableFuture<Void> shutdown(ThrowingRunnable runnable)
    {
        AtomicReference<Thread> stopThreadReference = new AtomicReference<>();
        CompletableFuture<Void> shutdown = new CompletableFuture<>()
        {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning)
            {
                boolean canceled = super.cancel(mayInterruptIfRunning);
                if (canceled && mayInterruptIfRunning)
                {
                    Thread thread = stopThreadReference.get();
                    if (thread != null)
                        thread.interrupt();
                }

                return canceled;
            }
        };

        Thread stopThread = new Thread(() ->
        {
            try
            {
                runnable.run();
                shutdown.complete(null);
            }
            catch (Throwable t)
            {
                shutdown.completeExceptionally(t);
            }
        });
        stopThread.setDaemon(true);
        stopThreadReference.set(stopThread);
        stopThread.start();
        return shutdown;
    }

    @FunctionalInterface
    interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
