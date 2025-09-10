//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;

/**
 * A striped thread pool with queues of jobs to execute.
 */
@ManagedObject("A striped thread pool")
public class StripedQueuedThreadPool extends ContainerLifeCycle implements ThreadFactory, SizedThreadPool, Dumpable, TryExecutor, VirtualThreads.Configurable
{
    private static final int STRIPES = 16;

    private final QueuedThreadPool[] queuedThreadPools = new QueuedThreadPool[STRIPES];

    private QueuedThreadPool pickQTP()
    {
        int idx = ThreadLocalRandom.current().nextInt(STRIPES);
        return queuedThreadPools[idx];
    }

    public StripedQueuedThreadPool()
    {
        this(200);
    }

    public StripedQueuedThreadPool(@Name("maxThreads") int maxThreads)
    {
        this(maxThreads, Math.min(8, maxThreads));
    }

    public StripedQueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads)
    {
        if (maxThreads < minThreads)
            throw new IllegalArgumentException("max threads (" + maxThreads + ") less than min threads (" + minThreads + ")");
        for (int i = 0; i < queuedThreadPools.length; i++)
        {
            queuedThreadPools[i] = new QueuedThreadPool();
        }
        setMinThreads(minThreads);
        setMaxThreads(maxThreads);
        setIdleTimeout(60000);
        setStopTimeout(5000);
        setReservedThreads(-1);
//        setThreadPoolBudget(new ThreadPoolBudget(this));
    }

    public void setThreadPoolBudget(ThreadPoolBudget budget)
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setThreadPoolBudget(budget);
        }
    }

    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        int reserved;
        if (reservedThreads >= 0)
        {
            reserved = reservedThreads / STRIPES;
        }
        else
        {
            int cpus = ProcessorUtils.availableProcessors();
            int threads = getMaxThreads() / STRIPES;
            reserved = Math.max(1, MathUtils.ceilToNextPowerOfTwo(Math.min(cpus, threads / 8)));
        }

        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setReservedThreads(reserved);
        }
    }

    public void setStopTimeout(long stopTimeout)
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setStopTimeout(stopTimeout);
        }
    }

    public void setIdleTimeout(int idleTimeout)
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setIdleTimeout(idleTimeout);
        }
    }

    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        for (int i = 0; i < queuedThreadPools.length; i++)
        {
            QueuedThreadPool queuedThreadPool = queuedThreadPools[i];
            queuedThreadPool.setName(name + '|' + i);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.start();
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.stop();
        }
    }

    @Override
    public int getMinThreads()
    {
        return queuedThreadPools[0].getMinThreads();
    }

    @Override
    public int getMaxThreads()
    {
        return queuedThreadPools[0].getMaxThreads() * STRIPES;
    }

    @Override
    public void join() throws InterruptedException
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.join();
        }
    }

    @Override
    public int getThreads()
    {
        int total = 0;
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            total += queuedThreadPool.getThreads();
        }
        return total;
    }

    @Override
    public int getIdleThreads()
    {
        int total = 0;
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            total += queuedThreadPool.getIdleThreads();
        }
        return total;
    }

    @Override
    public boolean isLowOnThreads()
    {
        boolean low = false;
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            low |= queuedThreadPool.isLowOnThreads();
        }
        return low;
    }

    @Override
    public void setMinThreads(int threads)
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setMinThreads(threads);
        }
    }

    @Override
    public void setMaxThreads(int threads)
    {
        for (QueuedThreadPool queuedThreadPool : queuedThreadPools)
        {
            queuedThreadPool.setMaxThreads(threads / STRIPES);
        }
    }

    @Override
    public Thread newThread(Runnable r)
    {
        return pickQTP().newThread(r);
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        return pickQTP().tryExecute(task);
    }

    @Override
    public void execute(Runnable task)
    {
        pickQTP().execute(task);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        DumpableCollection pools = new DumpableCollection("threadpools", Arrays.asList(queuedThreadPools));
        dumpObjects(out, indent, pools);
    }
}
