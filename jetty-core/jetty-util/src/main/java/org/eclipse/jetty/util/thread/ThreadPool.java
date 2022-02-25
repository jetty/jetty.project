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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.Executor;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * <p>A pool for threads.</p>
 * <p>A specialization of Executor interface that provides reporting methods (eg {@link #getThreads()})
 * and the option of configuration methods (e.g. @link {@link SizedThreadPool#setMaxThreads(int)}).</p>
 */
@ManagedObject("Pool of Threads")
public interface ThreadPool extends Executor
{
    /**
     * Blocks until the thread pool is {@link LifeCycle#stop stopped}.
     *
     * @throws InterruptedException if thread was interrupted
     */
    public void join() throws InterruptedException;

    /**
     * @return The total number of threads currently in the pool
     */
    @ManagedAttribute("number of threads in pool")
    public int getThreads();

    /**
     * @return The number of idle threads in the pool
     */
    @ManagedAttribute("number of idle threads in pool")
    public int getIdleThreads();

    /**
     * @return True if the pool is low on threads
     */
    @ManagedAttribute("indicates the pool is low on available threads")
    public boolean isLowOnThreads();

    /**
     * <p>Specialized sub-interface of ThreadPool that allows to get/set
     * the minimum and maximum number of threads of the pool.</p>
     */
    public interface SizedThreadPool extends ThreadPool
    {
        /**
         * @return the minimum number of threads
         */
        int getMinThreads();

        /**
         * @return the maximum number of threads
         */
        int getMaxThreads();

        /**
         * @param threads the minimum number of threads
         */
        void setMinThreads(int threads);

        /**
         * @param threads the maximum number of threads
         */
        void setMaxThreads(int threads);

        /**
         * @return a ThreadPoolBudget for this sized thread pool,
         * or null of no ThreadPoolBudget can be returned
         */
        default ThreadPoolBudget getThreadPoolBudget()
        {
            return null;
        }
    }
}
