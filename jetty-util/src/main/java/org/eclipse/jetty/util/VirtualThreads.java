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
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Utility class to use to query the runtime for virtual thread support,
 * and, if virtual threads are supported, to start virtual threads.</p>
 *
 * @see #areSupported()
 * @see #getVirtualThreadsExecutor(Executor)
 * @see #isVirtualThread()
 */
public class VirtualThreads
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreads.class);
    private static final Executor executor = probeVirtualThreadExecutor();
    private static final Method isVirtualThread = probeIsVirtualThread();

    private static Executor probeVirtualThreadExecutor()
    {
        try
        {
            return (Executor)Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
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

    private static Method getIsVirtualThreadMethod()
    {
        return isVirtualThread;
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
        return executor != null;
    }

    /**
     * <p>Starts a virtual thread to execute the given task, or throws
     * {@link UnsupportedOperationException} if virtual threads are not
     * supported.</p>
     *
     * @param task the task to execute in a virtual thread
     * @see #areSupported()
     * @deprecated use {@link #getVirtualThreadsExecutor(Executor)} instead
     */
    @Deprecated(forRemoval = true)
    public static void executeOnVirtualThread(Runnable task)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Starting in virtual thread: {}", task);
            getDefaultVirtualThreadsExecutor().execute(task);
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
            return (Boolean)getIsVirtualThreadMethod().invoke(Thread.currentThread());
        }
        catch (Throwable x)
        {
            warn();
            return false;
        }
    }

    /**
     * @return a default virtual thread per task {@code Executor}
     */
    public static Executor getDefaultVirtualThreadsExecutor()
    {
        return executor;
    }

    /**
     * @param executor the {@code Executor} to obtain a virtual threads {@code Executor} from
     * @return a virtual threads {@code Executor} obtained from the given {@code Executor}
     */
    public static Executor getVirtualThreadsExecutor(Executor executor)
    {
        if (executor instanceof Configurable)
            return ((Configurable)executor).getVirtualThreadsExecutor();
        return null;
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
            return ((Configurable)executor).getVirtualThreadsExecutor() != null;
        return false;
    }

    /**
     * <p>Implementations of this interface can be configured to use virtual threads.</p>
     * <p>Whether virtual threads are actually used depends on whether the runtime
     * supports virtual threads and, if the runtime supports them, whether they are
     * configured to be used via {@link #setVirtualThreadsExecutor(Executor)}.</p>
     */
    public interface Configurable
    {
        /**
         * @return the {@code Executor} to use to execute tasks in virtual threads
         */
        default Executor getVirtualThreadsExecutor()
        {
            return null;
        }

        /**
         *
         * @param executor the {@code Executor} to use to execute tasks in virtual threads
         * @throws UnsupportedOperationException if the runtime does not support virtual threads
         * @see #areSupported()
         */
        default void setVirtualThreadsExecutor(Executor executor)
        {
            if (executor != null && !VirtualThreads.areSupported())
            {
                warn();
                throw new UnsupportedOperationException();
            }
        }

        /**
         * @return whether to use virtual threads
         * @deprecated use {@link #getVirtualThreadsExecutor()} instead
         */
        @Deprecated(forRemoval = true)
        default boolean isUseVirtualThreads()
        {
            return getVirtualThreadsExecutor() != null;
        }

        /**
         * @param useVirtualThreads whether to use virtual threads
         * @throws UnsupportedOperationException if the runtime does not support virtual threads
         * @see #areSupported()
         * @deprecated use {@link #setVirtualThreadsExecutor(Executor)} instead
         */
        @Deprecated(forRemoval = true)
        default void setUseVirtualThreads(boolean useVirtualThreads)
        {
            setVirtualThreadsExecutor(useVirtualThreads ? executor : null);
        }
    }

    private VirtualThreads()
    {
    }
}
