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
import java.util.concurrent.Semaphore;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An implementation of {@link ThreadPool} interface that does not pool, but instead uses {@link VirtualThreads}.</p>
 * <p>It is possible to specify the max number of concurrent virtual threads that can be spawned, to help limiting
 * resource usage in applications, especially in case of load spikes, where an unlimited number of virtual threads
 * may be spawned, compete for resources, and eventually bring the system down due to memory exhaustion.</p>
 */
@ManagedObject("A thread non-pool for virtual threads")
public class VirtualThreadPool extends ContainerLifeCycle implements ThreadPool, Dumpable, TryExecutor, VirtualThreads.Configurable
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadPool.class);

    private final AutoLock.WithCondition _joinLock = new AutoLock.WithCondition();
    private String _name;
    private int _maxThreads;
    private boolean _tracking;
    private boolean _detailedDump;
    private Thread _keepAlive;
    private Executor _virtualExecutor;
    private boolean _externalExecutor;
    private volatile Semaphore _semaphore;

    public VirtualThreadPool()
    {
        this(200);
    }

    public VirtualThreadPool(int maxThreads)
    {
        if (!VirtualThreads.areSupported())
            throw new IllegalStateException("Virtual Threads not supported");
        _maxThreads = maxThreads;
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
     * @return the maximum number of concurrent virtual threads
     */
    @ManagedAttribute("The max number of concurrent virtual threads")
    public int getMaxThreads()
    {
        return _maxThreads;
    }

    /**
     * @param maxThreads the maximum number of concurrent virtual threads
     */
    public void setMaxThreads(int maxThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _maxThreads = maxThreads;
    }

    /**
     * Get if this pool is tracking virtual threads.
     *
     * @return {@code true} if the virtual threads will be tracked.
     * @see TrackingExecutor
     */
    @ManagedAttribute("Whether virtual threads are tracked")
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

    @ManagedAttribute("Whether to report additional details in the dump")
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
        _keepAlive = new Thread("jetty-virtual-thread-pool-keepalive")
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
        _keepAlive.start();

        if (_virtualExecutor == null)
        {
            _virtualExecutor = Objects.requireNonNull(StringUtil.isBlank(_name)
                ? VirtualThreads.getDefaultVirtualThreadsExecutor()
                : VirtualThreads.getNamedVirtualThreadsExecutor(_name));
        }
        if (_tracking && !(_virtualExecutor instanceof TrackingExecutor))
            _virtualExecutor = new TrackingExecutor(_virtualExecutor, isDetailedDump());
        addBean(_virtualExecutor);

        if (_maxThreads > 0)
        {
            _semaphore = new Semaphore(_maxThreads);
            addBean(_semaphore);
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_semaphore);
        _semaphore = null;
        removeBean(_virtualExecutor);
        if (!_externalExecutor)
            _virtualExecutor = null;
        _keepAlive = null;
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
            execute(task);
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
        Runnable job = task;
        Semaphore semaphore = _semaphore;
        if (semaphore != null)
        {
            job = () ->
            {
                try
                {
                    // The caller of execute(Runnable) cannot be blocked,
                    // as it is unknown whether it is a virtual thread.
                    // But this is a virtual thread, so acquiring a permit here
                    // blocks the virtual thread, but does not pin the carrier.
                    semaphore.acquire();
                    task.run();
                }
                catch (InterruptedException x)
                {
                    // Likely stopping this component, exit.
                    if (LOG.isDebugEnabled())
                        LOG.debug("interrupted while waiting for permit {}", task, x);
                }
                finally
                {
                    semaphore.release();
                }
            };
        }
        _virtualExecutor.execute(job);
    }
}
