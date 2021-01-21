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
    void join() throws InterruptedException;

    /**
     * @return The total number of threads currently in the pool
     */
    @ManagedAttribute("number of threads in pool")
    int getThreads();

    /**
     * @return The number of idle threads in the pool
     */
    @ManagedAttribute("number of idle threads in pool")
    int getIdleThreads();

    /**
     * @return True if the pool is low on threads
     */
    @ManagedAttribute("indicates the pool is low on available threads")
    boolean isLowOnThreads();

    /**
     * <p>Specialized sub-interface of ThreadPool that allows to get/set
     * the minimum and maximum number of threads of the pool.</p>
     */
    interface SizedThreadPool extends ThreadPool
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
