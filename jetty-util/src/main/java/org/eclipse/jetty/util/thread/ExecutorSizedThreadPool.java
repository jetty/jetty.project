//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * A {@link org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool} wrapper around {@link ThreadPoolExecutor}.
 */
public class ExecutorSizedThreadPool extends AbstractLifeCycle implements ThreadPool.SizedThreadPool
{
    private final ThreadPoolExecutor executor;

    public ExecutorSizedThreadPool()
    {
        this(new ThreadPoolExecutor(8, 200, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
    }

    public ExecutorSizedThreadPool(ThreadPoolExecutor executor)
    {
        this.executor = executor;
    }

    @Override
    public int getMinThreads()
    {
        return executor.getCorePoolSize();
    }

    @Override
    public int getMaxThreads()
    {
        return executor.getMaximumPoolSize();
    }

    @Override
    public void setMinThreads(int threads)
    {
        executor.setCorePoolSize(threads);
    }

    @Override
    public void setMaxThreads(int threads)
    {
        executor.setMaximumPoolSize(threads);
    }

    @Override
    public int getThreads()
    {
        return executor.getPoolSize();
    }

    @Override
    public int getIdleThreads()
    {
        return getThreads() - executor.getActiveCount();
    }

    @Override
    public void execute(Runnable command)
    {
        executor.execute(command);
    }

    @Override
    public boolean isLowOnThreads()
    {
        return getThreads() == getMaxThreads() && executor.getQueue().size() >= getIdleThreads();
    }

    @Override
    protected void doStop() throws Exception
    {
        executor.shutdownNow();
    }

    @Override
    public void join() throws InterruptedException
    {
        executor.awaitTermination(getStopTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public ThreadPoolBudget getThreadPoolBudget()
    {
        return new ThreadPoolBudget(this);
    }
}
