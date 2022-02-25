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

package org.eclipse.jetty.http.spi;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.TryExecutor;

public class DelegatingThreadPool extends ContainerLifeCycle implements ThreadPool, TryExecutor
{
    private Executor _executor; // memory barrier provided by start/stop semantics
    private TryExecutor _tryExecutor;

    public DelegatingThreadPool(Executor executor)
    {
        _executor = executor;
        _tryExecutor = TryExecutor.asTryExecutor(executor);
        addBean(_executor);
    }

    public Executor getExecutor()
    {
        return _executor;
    }

    public void setExecutor(Executor executor)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        updateBean(_executor, executor);
        _executor = executor;
        _tryExecutor = TryExecutor.asTryExecutor(executor);
    }

    @Override
    public void execute(Runnable job)
    {
        _executor.execute(job);
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        return _tryExecutor.tryExecute(task);
    }

    @Override
    public int getIdleThreads()
    {
        final Executor executor = _executor;
        if (executor instanceof ThreadPool)
            return ((ThreadPool)executor).getIdleThreads();

        if (executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)executor;
            return tpe.getPoolSize() - tpe.getActiveCount();
        }
        return -1;
    }

    @Override
    public int getThreads()
    {
        final Executor executor = _executor;
        if (executor instanceof ThreadPool)
            return ((ThreadPool)executor).getThreads();

        if (executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)executor;
            return tpe.getPoolSize();
        }
        return -1;
    }

    @Override
    public boolean isLowOnThreads()
    {
        final Executor executor = _executor;
        if (executor instanceof ThreadPool)
            return ((ThreadPool)executor).isLowOnThreads();

        if (executor instanceof ThreadPoolExecutor)
        {
            final ThreadPoolExecutor tpe = (ThreadPoolExecutor)executor;
            // getActiveCount() locks the thread pool, so execute it last
            return tpe.getPoolSize() == tpe.getMaximumPoolSize() &&
                tpe.getQueue().size() >= tpe.getPoolSize() - tpe.getActiveCount();
        }
        return false;
    }

    @Override
    public void join() throws InterruptedException
    {
        final Executor executor = _executor;
        if (executor instanceof ThreadPool)
            ((ThreadPool)executor).join();
        else if (executor instanceof ExecutorService)
            ((ExecutorService)executor).awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        else
            throw new IllegalStateException();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (!(_executor instanceof LifeCycle) && (_executor instanceof ExecutorService))
            ((ExecutorService)_executor).shutdownNow();
    }
}
