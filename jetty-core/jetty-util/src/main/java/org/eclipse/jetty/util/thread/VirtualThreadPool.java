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

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link ThreadPool} interface that does not pool, but instead uses {@link VirtualThreads}.
 */
public class VirtualThreadPool extends ContainerLifeCycle implements ThreadFactory, ThreadPool, Dumpable, TryExecutor, VirtualThreads.Configurable
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadPool.class);

    private String _name = null;
    private Set<Thread> _threads;
    private VirtualThreads.ThreadFactoryExecutor _virtualExecutor;
    private Thread _main;
    private final AutoLock.WithCondition _joinLock = new AutoLock.WithCondition();

    public VirtualThreadPool()
    {
        if (!VirtualThreads.areSupported())
            throw new IllegalStateException("Virtual Threads not supported");
    }

    /**
     * @return the name of this thread pool
     */
    @ManagedAttribute("name of the thread pool")
    public String getName()
    {
        return _name;
    }

    /**
     * <p>Sets the name of this thread pool, used as a prefix for the thread names.</p>
     *
     * @param name the name of this thread pool
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        if (StringUtil.isBlank(name) && name != null)
            throw new IllegalArgumentException("Blank name");
        _name = name;
    }

    public void setTracking(boolean tracking)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _threads = tracking ? Collections.newSetFromMap(new WeakHashMap<>()) : null;
    }

    public boolean isTracking()
    {
        return _threads != null;
    }

    @Override
    protected void doStart() throws Exception
    {
        _main = new Thread("virtual main")
        {
            @Override
            public void run()
            {
                try (AutoLock.WithCondition l = _joinLock.lock())
                {
                    while (isRunning())
                    {
                        l.await();
                    }
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
        };
        _main.start();

        _virtualExecutor = Objects.requireNonNull(StringUtil.isBlank(_name)
            ? VirtualThreads.getDefaultVirtualThreadFactoryExecutor()
            : VirtualThreads.getNamedVirtualThreadFactoryExecutor(_name));
        if (_threads != null)
            _virtualExecutor = new TrackingVirtualExecutor(_virtualExecutor);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _virtualExecutor = null;
        _main = null;

        try (AutoLock.WithCondition l = _joinLock.lock())
        {
            l.signalAll();
        }
    }

    @Override
    public Thread newThread(Runnable r)
    {
        return _virtualExecutor.newThread(r);
    }

    @Override
    public Executor getVirtualThreadsExecutor()
    {
        return _virtualExecutor;
    }

    @Override
    public void setVirtualThreadsExecutor(Executor executor)
    {
        throw new UnsupportedOperationException("cannot set VirtualThreadExecutor");
    }

    @Override
    public void join() throws InterruptedException
    {
        try (AutoLock.WithCondition l = _joinLock.lock())
        {
            while (isRunning())
            {
                l.await();
            }
        }

        while (isStopping())
        {
            Thread.onSpinWait();
        }
    }

    @Override
    public int getThreads()
    {
        return -1;
    }

    @Override
    public int getIdleThreads()
    {
        return -1;
    }

    @Override
    public boolean isLowOnThreads()
    {
        return false;
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        try
        {
            _virtualExecutor.execute(task);
            return true;
        }
        catch (RejectedExecutionException e)
        {
            LOG.warn("tryExecute {} failed", _name, e);
        }
        return false;
    }

    @Override
    public void execute(Runnable task)
    {
        _virtualExecutor.execute(task);
    }

    private class TrackingVirtualExecutor implements VirtualThreads.ThreadFactoryExecutor
    {
        private final VirtualThreads.ThreadFactoryExecutor _threadFactoryExecutor;

        private TrackingVirtualExecutor(VirtualThreads.ThreadFactoryExecutor threadFactoryExecutor)
        {
            _threadFactoryExecutor = threadFactoryExecutor;
        }

        @Override
        public void execute(Runnable task)
        {
            _threadFactoryExecutor.execute(() ->
            {
                try
                {
                    _threads.add(Thread.currentThread());
                    task.run();
                }
                finally
                {
                    _threads.remove(Thread.currentThread());
                }
            });
        }

        @Override
        public Thread newThread(Runnable task)
        {
            Thread thread = _threadFactoryExecutor.newThread(() ->
            {
                try
                {
                    task.run();
                }
                finally
                {
                    _threads.remove(Thread.currentThread());
                }
            });
            _threads.add(thread);
            return thread;
        }
    }
}
