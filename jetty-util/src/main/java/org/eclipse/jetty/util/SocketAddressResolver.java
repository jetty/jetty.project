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

package org.eclipse.jetty.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Creates {@link SocketAddress} instances, returning them through a {@link Promise}.</p>
 */
public interface SocketAddressResolver
{
    /**
     * Resolves via DNS the given host and port, within the connect timeout,
     * returning a list of {@link InetSocketAddress} through the given {@link Promise}.
     *
     * @param host the host to resolve
     * @param port the port of the resulting socket address
     * @param promise the callback invoked when the resolution succeeds or fails
     */
    public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise);

    /**
     * <p>Creates {@link InetSocketAddress} instances synchronously in the caller thread.</p>
     */
    @ManagedObject("The synchronous address resolver")
    public static class Sync implements SocketAddressResolver
    {
        @Override
        public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
        {
            try
            {
                InetAddress[] addresses = InetAddress.getAllByName(host);

                List<InetSocketAddress> result = new ArrayList<>(addresses.length);
                for (InetAddress address : addresses)
                {
                    result.add(new InetSocketAddress(address, port));
                }

                if (result.isEmpty())
                    promise.failed(new UnknownHostException());
                else
                    promise.succeeded(result);
            }
            catch (Throwable x)
            {
                promise.failed(x);
            }
        }
    }

    /**
     * <p>Creates {@link InetSocketAddress} instances asynchronously in a different thread.</p>
     * <p>{@link InetSocketAddress#InetSocketAddress(String, int)} attempts to perform a DNS
     * resolution of the host name, and this may block for several seconds.
     * This class creates the {@link InetSocketAddress} in a separate thread and provides the result
     * through a {@link Promise}, with the possibility to specify a timeout for the operation.</p>
     * <p>Example usage:</p>
     * <pre>
     * SocketAddressResolver resolver = new SocketAddressResolver.Async(executor, scheduler, timeout);
     * resolver.resolve("www.google.com", 80, new Promise&lt;SocketAddress&gt;()
     * {
     *     public void succeeded(SocketAddress result)
     *     {
     *         // The address was resolved
     *     }
     *
     *     public void failed(Throwable failure)
     *     {
     *         // The address resolution failed
     *     }
     * });
     * </pre>
     */
    @ManagedObject("The asynchronous address resolver")
    public static class Async implements SocketAddressResolver
    {
        private static final Logger LOG = LoggerFactory.getLogger(SocketAddressResolver.class);

        private final Executor executor;
        private final Scheduler scheduler;
        private final long timeout;

        /**
         * Creates a new instance with the given executor (to perform DNS resolution in a separate thread),
         * the given scheduler (to cancel the operation if it takes too long) and the given timeout, in milliseconds.
         *
         * @param executor the thread pool to use to perform DNS resolution in pooled threads
         * @param scheduler the scheduler to schedule tasks to cancel DNS resolution if it takes too long
         * @param timeout the timeout, in milliseconds, for the DNS resolution to complete
         */
        public Async(Executor executor, Scheduler scheduler, long timeout)
        {
            this.executor = executor;
            this.scheduler = scheduler;
            this.timeout = timeout;
        }

        public Executor getExecutor()
        {
            return executor;
        }

        public Scheduler getScheduler()
        {
            return scheduler;
        }

        @ManagedAttribute(value = "The timeout, in milliseconds, to resolve an address", readonly = true)
        public long getTimeout()
        {
            return timeout;
        }

        @Override
        public void resolve(final String host, final int port, final Promise<List<InetSocketAddress>> promise)
        {
            executor.execute(() ->
            {
                Scheduler.Task task = null;
                final AtomicBoolean complete = new AtomicBoolean();
                if (timeout > 0)
                {
                    final Thread thread = Thread.currentThread();
                    task = scheduler.schedule(() ->
                    {
                        if (complete.compareAndSet(false, true))
                        {
                            promise.failed(new TimeoutException("DNS timeout " + getTimeout() + " ms"));
                            thread.interrupt();
                        }
                    }, timeout, TimeUnit.MILLISECONDS);
                }

                try
                {
                    long start = NanoTime.now();
                    InetAddress[] addresses = InetAddress.getAllByName(host);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Resolved {} in {} ms", host, NanoTime.millisElapsedFrom(start));

                    List<InetSocketAddress> result = new ArrayList<>(addresses.length);
                    for (InetAddress address : addresses)
                    {
                        result.add(new InetSocketAddress(address, port));
                    }

                    if (complete.compareAndSet(false, true))
                    {
                        if (result.isEmpty())
                            promise.failed(new UnknownHostException());
                        else
                            promise.succeeded(result);
                    }
                }
                catch (Throwable x)
                {
                    if (complete.compareAndSet(false, true))
                        promise.failed(x);
                }
                finally
                {
                    if (task != null)
                        task.cancel();
                }
            });
        }
    }
}
