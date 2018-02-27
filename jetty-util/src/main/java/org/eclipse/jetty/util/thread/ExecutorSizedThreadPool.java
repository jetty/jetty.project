//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * A {@link org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool} wrapper around {@link ThreadPoolExecutor}.
 */
@ManagedObject("A thread pool")
public class ExecutorSizedThreadPool extends ContainerLifeCycle implements ThreadPool.SizedThreadPool, TryExecutor
{
    private final ThreadPoolExecutor _executor;
    private int _minThreads;
    private int _reservedThreads = -1;
    private TryExecutor _tryExecutor = TryExecutor.NO_TRY;
    private ThreadPoolBudget _budget;

    public ExecutorSizedThreadPool()
    {
        this(200, 8);
    }

    public ExecutorSizedThreadPool(int maxThreads, int minThreads)
    {
        this(new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>()),minThreads,-1);
    }
    
    public ExecutorSizedThreadPool(ThreadPoolExecutor executor)
    {
        this(executor, -1);
    }

    public ExecutorSizedThreadPool(ThreadPoolExecutor executor, int reservedThreads)
    {
        this(executor, Math.min(Runtime.getRuntime().availableProcessors(),executor.getCorePoolSize()),-1);
    }
    
    private ExecutorSizedThreadPool(ThreadPoolExecutor executor, int minThreads, int reservedThreads)
    {
        _executor = executor;
        _minThreads = minThreads;
        _reservedThreads = reservedThreads;
    }

    @Override
    @ManagedAttribute("minimum number of threads in the pool")
    public int getMinThreads()
    {
        return _minThreads;
    }

    @Override
    @ManagedAttribute("maximum number of threads in the pool")
    public int getMaxThreads()
    {
        return _executor.getMaximumPoolSize();
    }

    /**
     * Get the maximum thread idle time.
     *
     * @return Max idle time in ms.
     * @see #setIdleTimeout
     */
    @ManagedAttribute("maximum time a thread may be idle in ms")
    public int getIdleTimeout()
    {
        return (int)_executor.getKeepAliveTime(TimeUnit.MILLISECONDS);
    }
    
    /**
     * Set the number of reserved threads.
     *
     * @param reservedThreads number of reserved threads or -1 for heuristically determined 
     * @see #getReservedThreads
     */
    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _reservedThreads = reservedThreads;
    }
    
    @Override
    public void setMinThreads(int threads)
    {
        _minThreads = threads;
    }

    @Override
    public void setMaxThreads(int threads)
    {
        _executor.setCorePoolSize(threads);
        _executor.setMaximumPoolSize(threads);
    }

    /**
     * Set the maximum thread idle time.
     * Threads that are idle for longer than this period may be
     * stopped.
     *
     * @param idleTimeout Max idle time in ms.
     * @see #getIdleTimeout
     */
    public void setIdleTimeout(int idleTimeout)
    {
        _executor.setKeepAliveTime(idleTimeout,TimeUnit.MILLISECONDS);
    }
    
    
    
    @Override
    @ManagedAttribute("number of threads in the pool")
    public int getThreads()
    {
        return _executor.getPoolSize();
    }

    @Override
    @ManagedAttribute("number of idle threads in the pool")
    public int getIdleThreads()
    {
        return _executor.getPoolSize()-_executor.getActiveCount();
    }

    /**
     * Get the number of reserved threads.
     *
     * @return number of reserved threads or or -1 for heuristically determined
     * @see #setReservedThreads
     */
    @ManagedAttribute("the number of reserved threads in the pool")
    public int getReservedThreads()
    {
        if (isStarted())
            return getBean(ReservedThreadExecutor.class).getCapacity();
        return _reservedThreads;
    }

    @Override
    public void execute(Runnable command)
    {
        _executor.execute(command);
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        TryExecutor tryExecutor = _tryExecutor;
        return tryExecutor!=null && tryExecutor.tryExecute(task);
    }

    @Override
    @ManagedAttribute(value = "thread pool is low on threads", readonly = true)
    public boolean isLowOnThreads()
    {
        return getThreads() == getMaxThreads() && _executor.getQueue().size() >= getIdleThreads();
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_executor.isShutdown())
            throw new IllegalStateException("Not restartable");
        for (int i=_minThreads;i-->0;)
            _executor.prestartCoreThread();
        _tryExecutor = new ReservedThreadExecutor(this,_reservedThreads);
        addBean(_tryExecutor);
        
        super.doStart();
    }
    
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_tryExecutor);
        _tryExecutor = TryExecutor.NO_TRY;
        _executor.shutdownNow();
        _budget.reset();
    }

    @Override
    public void join() throws InterruptedException
    {
        _executor.awaitTermination(getStopTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public ThreadPoolBudget getThreadPoolBudget()
    {
        if (_budget==null)
            _budget = new ThreadPoolBudget(this);
        return _budget;
    }

    public void setThreadsPriority(int i)
    {
        // TODO implement
    }
    
    public int getThreadsPriority()
    {
        return 0;
    }
}
