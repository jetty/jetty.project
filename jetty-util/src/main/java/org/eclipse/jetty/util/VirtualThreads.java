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

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Utility class to use to query the runtime for virtual thread support,
 * and, if virtual threads are supported, to start virtual threads.</p>
 *
 * @see #areSupported()
 * @see #startVirtualThread(Runnable)
 * @see #isVirtualThread()
 */
public class VirtualThreads
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreads.class);
    private static final Method startVirtualThread = probeStartVirtualThread();
    private static final Method isVirtualThread = probeIsVirtualThread();

    private static Method probeStartVirtualThread()
    {
        try
        {
            return Thread.class.getMethod("startVirtualThread", Runnable.class);
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    private static Method probeIsVirtualThread()
    {
        try
        {
            return Thread.class.getMethod("isVirtual");
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    private static void warn()
    {
        LOG.warn("Virtual thread support is not available (or not enabled via --enable-preview) in the current Java runtime ({})", System.getProperty("java.version"));
    }

    /**
     * @return whether the runtime supports virtual threads
     */
    public static boolean areSupported()
    {
        return startVirtualThread != null;
    }

    /**
     * <p>Starts a virtual thread to execute the given task, or throws
     * {@link UnsupportedOperationException} if virtual threads are not
     * supported.</p>
     *
     * @param task the task to execute in a virtual thread
     * @see #areSupported()
     */
    public static void startVirtualThread(Runnable task)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Starting in virtual thread: {}", task);
            startVirtualThread.invoke(null, task);
        }
        catch (Throwable x)
        {
            warn();
            throw new UnsupportedOperationException(x);
        }
    }

    /**
     * @return whether the current thread is a virtual thread
     */
    public static boolean isVirtualThread()
    {
        try
        {
            return (Boolean)isVirtualThread.invoke(Thread.currentThread());
        }
        catch (Throwable x)
        {
            warn();
            return false;
        }
    }

    /**
     * <p>Tests whether the given executor implements {@link Configurable} and
     * it has been configured to use virtual threads.</p>
     *
     * @param executor the Executor to test
     * @return whether the given executor implements {@link Configurable}
     * and it has been configured to use virtual threads
     */
    public static boolean isUseVirtualThreads(Executor executor)
    {
        if (executor instanceof Configurable)
            return ((Configurable)executor).isUseVirtualThreads();
        return false;
    }

    /**
     * <p>Implementations of this interface can be configured to use virtual threads.</p>
     * <p>Whether virtual threads are actually used depends on whether the runtime
     * supports virtual threads and, if the runtime supports them, whether they are
     * configured to be used via {@link #setUseVirtualThreads(boolean)}.</p>
     */
    public interface Configurable
    {
        /**
         * @return whether to use virtual threads
         */
        default boolean isUseVirtualThreads()
        {
            return false;
        }

        /**
         * @param useVirtualThreads whether to use virtual threads
         * @throws UnsupportedOperationException if the runtime does not support virtual threads
         * @see #areSupported()
         */
        default void setUseVirtualThreads(boolean useVirtualThreads)
        {
            if (useVirtualThreads && !VirtualThreads.areSupported())
            {
                warn();
                throw new UnsupportedOperationException();
            }
        }
    }

    private VirtualThreads()
    {
    }
}
