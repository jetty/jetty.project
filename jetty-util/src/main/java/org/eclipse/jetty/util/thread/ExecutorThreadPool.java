// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

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

/* ------------------------------------------------------------ */
/** Jetty ThreadPool using java 5 ThreadPoolExecutor
 * This class wraps a {@link ExecutorService} as a {@link ThreadPool} and 
 * {@link LifeCycle} interfaces so that it may be used by the Jetty {@link org.eclipse.jetty.Server}
 * 
 * 
 *
 */
public class ExecutorThreadPool extends AbstractLifeCycle implements ThreadPool, LifeCycle
{
    private final ExecutorService _executor;

    /* ------------------------------------------------------------ */
    public ExecutorThreadPool(ExecutorService executor)
    {
        _executor=executor;
    }
    
    /* ------------------------------------------------------------ */
    /** constructor.
     * Wraps an {@link ThreadPoolExecutor}.
     * Core size is 32, max pool size is 256, pool thread timeout after 60 seconds and
     * an unbounded {@link LinkedBlockingQueue} is used for the job queue;
     */
    public ExecutorThreadPool()
    {
        this(new ThreadPoolExecutor(32,256,60,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>()));
    }
    
    /* ------------------------------------------------------------ */
    /** constructor.
     * Wraps an {@link ThreadPoolExecutor}.
     * Core size is 32, max pool size is 256, pool thread timeout after 60 seconds
     * @param queueSize if -1, an unbounded {@link LinkedBlockingQueue} is used, if 0 then a
     * {@link SynchronousQueue} is used, other a {@link ArrayBlockingQueue} of the given size is used.
     */
    public ExecutorThreadPool(int queueSize)
    {
        this(new ThreadPoolExecutor(32,256,60,TimeUnit.SECONDS,
                queueSize<0?new LinkedBlockingQueue<Runnable>()
                        : (queueSize==0?new SynchronousQueue<Runnable>()
                                :new ArrayBlockingQueue<Runnable>(queueSize))));
    }

    /* ------------------------------------------------------------ */
    /** constructor.
     * Wraps an {@link ThreadPoolExecutor} using
     * an unbounded {@link LinkedBlockingQueue} is used for the jobs queue;
     */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime)
    {
        this(new ThreadPoolExecutor(corePoolSize,maximumPoolSize,keepAliveTime,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>()));
    }

    /* ------------------------------------------------------------ */
    /** constructor.
     * Wraps an {@link ThreadPoolExecutor} using
     * an unbounded {@link LinkedBlockingQueue} is used for the jobs queue;
     */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit)
    {
        this(new ThreadPoolExecutor(corePoolSize,maximumPoolSize,keepAliveTime,unit,new LinkedBlockingQueue<Runnable>()));
    }

    /* ------------------------------------------------------------ */
    public ExecutorThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue)
    {
        this(new ThreadPoolExecutor(corePoolSize,maximumPoolSize,keepAliveTime,unit,workQueue));
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
            Log.warn(e);
            return false;
        }
    }

    /* ------------------------------------------------------------ */
    public int getIdleThreads()
    {
        if (_executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)_executor;
            return tpe.getPoolSize() -tpe.getActiveCount();
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
            return tpe.getActiveCount()>=tpe.getMaximumPoolSize();
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public void join() throws InterruptedException
    {
        _executor.awaitTermination(Long.MAX_VALUE,TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _executor.shutdownNow();
    }

}
