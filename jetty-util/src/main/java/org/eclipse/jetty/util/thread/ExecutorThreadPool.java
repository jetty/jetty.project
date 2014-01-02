//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * Jetty ThreadPool using java 5 ThreadPoolExecutor
 * This class wraps a {@link ExecutorService} as a {@link ThreadPool} and
 * {@link LifeCycle} interfaces so that it may be used by the Jetty <code>org.eclipse.jetty.server.Server</code>
 */
public class ExecutorThreadPool extends AbstractLifeCycle implements ThreadPool, LifeCycle
{
    private static final Logger LOG = Log.getLogger(ExecutorThreadPool.class);
    private final ExecutorService _executor;

    /* ------------------------------------------------------------ */
    public ExecutorThreadPool(ExecutorService executor)
    {
        _executor = executor;
    }

    /* ------------------------------------------------------------ */
    /**
     * Wraps an {@link ThreadPoolExecutor}.
     * Max pool size is 256, pool thread timeout after 60 seconds and
     * an unbounded {@link LinkedBlockingQueue} is used for the job queue;
     */
    public ExecutorThreadPool()
    {
        // Using an unbounded queue makes the maxThreads parameter useless
        // Refer to ThreadPoolExecutor javadocs for details
        this(new ThreadPoolExecutor(256, 256, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()));
    }

    /* ------------------------------------------------------------ */
    /**
     * Wraps an {@link ThreadPoolExecutor}.
     * Max pool size is 256, pool thread timeout after 60 seconds, and core pool size is 32 when queueSize >= 0.
     * @param queueSize can be -1 for using an unbounded {@link LinkedBlockingQueue}, 0 for using a
     * {@link SynchronousQueue}, greater than 0 for using a {@link ArrayBlockingQueue} of the given size.
     */
    public ExecutorThreadPool(int queueSize)
    {
        this(queueSize < 0 ? new ThreadPoolExecutor(256, 256, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()) :
                queueSize == 0 ? new ThreadPoolExecutor(32, 256, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) :
                        new ThreadPoolExecutor(32, 256, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queueSize)));
    }

    /* ------------------------------------------------------------ */
    /**
     * Wraps an {@link ThreadPoolExecutor} using
     * an unbounded {@link LinkedBlockingQueue} is used for the jobs queue;
     * @param corePoolSize must be equal to maximumPoolSize
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime the max time a thread can remain idle, in milliseconds
     */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime)
    {
        this(corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------ */
    /**
     * Wraps an {@link ThreadPoolExecutor} using
     * an unbounded {@link LinkedBlockingQueue} is used for the jobs queue.
     * @param corePoolSize must be equal to maximumPoolSize
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime the max time a thread can remain idle
     * @param unit the unit for the keepAliveTime
     */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit)
    {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, new LinkedBlockingQueue<Runnable>());
    }

    /* ------------------------------------------------------------ */

    /**
     * Wraps an {@link ThreadPoolExecutor}
     * @param corePoolSize the number of threads to keep in the pool, even if they are idle
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime the max time a thread can remain idle
     * @param unit the unit for the keepAliveTime
     * @param workQueue the queue to use for holding tasks before they are executed
     */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue)
    {
        this(new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue));
    }

    /* ------------------------------------------------------------ */
    public boolean dispatch(Runnable job)
    {
        try
        {
            _executor.execute(job);
            return true;
        }
        catch(RejectedExecutionException e)
        {
            LOG.warn(e);
            return false;
        }
    }

    /* ------------------------------------------------------------ */
    public int getIdleThreads()
    {
        if (_executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)_executor;
            return tpe.getPoolSize() - tpe.getActiveCount();
        }
        return -1;
    }

    /* ------------------------------------------------------------ */
    public int getThreads()
    {
        if (_executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)_executor;
            return tpe.getPoolSize();
        }
        return -1;
    }

    /* ------------------------------------------------------------ */
    public boolean isLowOnThreads()
    {
        if (_executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)_executor;
            // getActiveCount() locks the thread pool, so execute it last
            return tpe.getPoolSize() == tpe.getMaximumPoolSize() &&
                    tpe.getQueue().size() >= tpe.getPoolSize() - tpe.getActiveCount();
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public void join() throws InterruptedException
    {
        _executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _executor.shutdownNow();
    }
}
