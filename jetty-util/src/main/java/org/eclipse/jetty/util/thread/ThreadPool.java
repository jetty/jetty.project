//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/* ------------------------------------------------------------ */
/** ThreadPool.
 * 
 * A specialization of Executor interface that provides reporting methods (eg {@link #getThreads()})
 * and the option of configuration methods (e.g. @link {@link SizedThreadPool#setMaxThreads(int)}). 
 *
 */
@ManagedObject("Pool of Threads")
public interface ThreadPool extends Executor
{
    /* ------------------------------------------------------------ */
    /**
     * Blocks until the thread pool is {@link LifeCycle#stop stopped}.
     * @throws InterruptedException if thread was interrupted
     */
    public void join() throws InterruptedException;

    /* ------------------------------------------------------------ */
    /**
     * @return The total number of threads currently in the pool
     */
    @ManagedAttribute("number of threads in pool")
    public int getThreads();

    /* ------------------------------------------------------------ */
    /**
     * @return The number of idle threads in the pool
     */
    @ManagedAttribute("number of idle threads in pool")
    public int getIdleThreads();
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if the pool is low on threads
     */
    @ManagedAttribute("indicates the pool is low on available threads")
    public boolean isLowOnThreads();
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface SizedThreadPool extends ThreadPool
    {
        public int getMinThreads();
        public int getMaxThreads();
        public void setMinThreads(int threads);
        public void setMaxThreads(int threads);
    }
}
