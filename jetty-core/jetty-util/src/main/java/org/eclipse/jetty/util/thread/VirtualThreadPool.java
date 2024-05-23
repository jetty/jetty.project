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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link ThreadPool} interface that does not pool, but instead uses {@link VirtualThreads}.
 */
@ManagedObject("A thread non-pool for virtual threads")
public class VirtualThreadPool extends ContainerLifeCycle implements ThreadPool, Dumpable, TryExecutor, VirtualThreads.Configurable
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadPool.class);

    private final AutoLock.WithCondition _joinLock = new AutoLock.WithCondition();
    private String _name = null;
    private Executor _virtualExecutor;
    private Thread _main;
    private boolean _externalExecutor;
    private boolean _tracking;
    private boolean _detailedDump;

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

    /**
     * Get if this pool is tracking virtual threads.
     * @return {@code true} if the virtual threads will be tracked.
     * @see TrackingExecutor
     */
    @ManagedAttribute("virtual threads are tracked")
    public boolean isTracking()
    {
        return _tracking;
    }

    public void setTracking(boolean tracking)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _tracking = tracking;
    }

    @ManagedAttribute("reports additional details in the dump")
    public boolean isDetailedDump()
    {
        return _detailedDump;
    }

    public void setDetailedDump(boolean detailedDump)
    {
        _detailedDump = detailedDump;
        if (_virtualExecutor instanceof TrackingExecutor trackingExecutor)
            trackingExecutor.setDetailedDump(detailedDump);
    }
    
    @Override
    protected void doStart() throws Exception
    {
        _main = new Thread("jetty-virtual-thread-pool-keepalive")
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

        if (_virtualExecutor == null)
        {
            _externalExecutor = false;
            _virtualExecutor = Objects.requireNonNull(StringUtil.isBlank(_name)
                ? VirtualThreads.getDefaultVirtualThreadsExecutor()
                : VirtualThreads.getNamedVirtualThreadsExecutor(_name));
        }
        if (_tracking && !(_virtualExecutor instanceof TrackingExecutor))
            _virtualExecutor = new TrackingExecutor(_virtualExecutor, _detailedDump);
        addBean(_virtualExecutor);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_virtualExecutor);
        if (!_externalExecutor)
            _virtualExecutor = null;
        _main = null;

        try (AutoLock.WithCondition l = _joinLock.lock())
        {
            l.signalAll();
        }
    }

    @Override
    public Executor getVirtualThreadsExecutor()
    {
        return _virtualExecutor;
    }

    @Override
    public void setVirtualThreadsExecutor(Executor executor)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _externalExecutor = executor != null;
        _virtualExecutor = executor;
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
        return _virtualExecutor instanceof TrackingExecutor tracking ? tracking.size() : -1;
    }

    @Override
    public int getIdleThreads()
    {
        return 0;
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
}
