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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A TryExecutor using pre-allocated/reserved threads from an external Executor.</p>
 * <p>Calls to {@link #tryExecute(Runnable)} on ReservedThreadExecutor will either
 * succeed with a reserved thread immediately being assigned the task, or fail if
 * no reserved thread is available.</p>
 * <p>Threads are reserved lazily, with new reserved threads being allocated from the external
 * {@link Executor} passed to the constructor. Whenever 1 or more reserved threads have been
 * idle for more than {@link #getIdleTimeoutMs()} then one reserved thread will return to
 * the external Executor.</p>
 */
@ManagedObject("A pool for reserved threads")
public class ReservedThreadExecutor extends ContainerLifeCycle implements TryExecutor, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ReservedThreadExecutor.class);

    private final Executor _executor;
    private final ThreadIdPool<ReservedThread> _threads;
    private final AtomicInteger _pending = new AtomicInteger();
    private final int _minSize;
    private final int _maxPending;
    private ThreadPoolBudget.Lease _lease;
    private long _idleTimeoutMs;

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads that can be reserved. If less than 0 then capacity
     *                 is calculated based on a heuristic from the number of available processors and
     *                 thread pool type.
     */
    public ReservedThreadExecutor(Executor executor, int capacity)
    {
        this(executor, capacity, -1);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads that can be reserved. If less than 0 then capacity
     *                 is calculated based on a heuristic from the number of available processors and
     *                 thread pool type.
     * @param minSize The minimum number of reserve Threads that the algorithm tries to maintain, or -1 for a heuristic value.
     */
    public ReservedThreadExecutor(Executor executor, int capacity, int minSize)
    {
        this(executor, capacity, minSize, -1);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads that can be reserved. If less than 0 then capacity
     *                 is calculated based on a heuristic from the number of available processors and
     *                 thread pool type.
     * @param minSize The minimum number of reserve Threads that the algorithm tries to maintain, or -1 for a heuristic value.
     * @param maxPending The maximum number of reserved Threads to start, or -1 for no limit.
     */
    public ReservedThreadExecutor(Executor executor, int capacity, int minSize, int maxPending)
    {
        _executor = executor;
        _threads = new ThreadIdPool<>(reservedThreads(executor, capacity));
        _minSize = minSize < 0 ? Math.min(1, _threads.capacity()) : minSize;
        if (_minSize > _threads.capacity())
            throw new IllegalArgumentException("minSize larger than capacity");
        _maxPending = maxPending;
        if (_maxPending == 0)
            throw new IllegalArgumentException("maxPending cannot be 0");
        if (LOG.isDebugEnabled())
            LOG.debug("{}", this);
        installBean(_executor);
        installBean(_threads);
    }

    /**
     * Get the heuristic number of reserved threads.
     *
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate, If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     * @return the number of reserved threads that would be used by a ReservedThreadExecutor
     * constructed with these arguments.
     */
    public static int reservedThreads(Executor executor, int capacity)
    {
        if (capacity >= 0)
            return capacity;
        if (VirtualThreads.isUseVirtualThreads(executor))
            return 0;
        int cpus = ProcessorUtils.availableProcessors();
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            int threads = ((ThreadPool.SizedThreadPool)executor).getMaxThreads();
            return Math.max(1, MathUtils.ceilToNextPowerOfTwo(Math.min(cpus, threads / 8)));
        }
        return cpus;
    }

    public Executor getExecutor()
    {
        return _executor;
    }

    /**
     * @return the maximum number of reserved threads
     */
    @ManagedAttribute(value = "max number of reserved threads", readonly = true)
    public int getCapacity()
    {
        return _threads.capacity();
    }

    /**
     * @return the number of threads available to {@link #tryExecute(Runnable)}
     */
    @ManagedAttribute(value = "available reserved threads", readonly = true)
    public int getAvailable()
    {
        return _threads.size();
    }

    @ManagedAttribute(value = "pending reserved threads (deprecated)", readonly = true)
    @Deprecated
    public int getPending()
    {
        return 0;
    }

    @ManagedAttribute(value = "idle timeout in ms", readonly = true)
    public long getIdleTimeoutMs()
    {
        return _idleTimeoutMs;
    }

    /**
     * Set the idle timeout for shrinking the reserved thread pool
     *
     * @param idleTime Time to wait before shrinking, or 0 for default timeout.
     * @param idleTimeUnit Time units for idle timeout
     */
    public void setIdleTimeout(long idleTime, TimeUnit idleTimeUnit)
    {
        if (isRunning())
            throw new IllegalStateException();
        _idleTimeoutMs = idleTime <= 0 ? 0 : idleTimeUnit.toMillis(idleTime);
    }

    @Override
    public void doStart() throws Exception
    {
        _lease = ThreadPoolBudget.leaseFrom(getExecutor(), this, getCapacity());
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        if (_lease != null)
            _lease.close();

        super.doStop();

        _threads.removeAll().forEach(ReservedThread::stop);
    }

    @Override
    public void execute(Runnable task) throws RejectedExecutionException
    {
        _executor.execute(task);
    }

    /**
     * <p>Executes the given task if and only if a reserved thread is available.</p>
     *
     * @param task the task to run
     * @return true if and only if a reserved thread was available and has been assigned the task to run.
     */
    @Override
    public boolean tryExecute(Runnable task)
    {
        if (task == null)
            return false;

        ReservedThread reserved = _threads.take();
        if (reserved != null)
        {
            reserved.wakeup(task);
            return true;
        }

        startReservedThread();

        if (LOG.isDebugEnabled())
            LOG.debug("{} tryExecute failed for {}", this, task);
        return false;
    }

    private void startReservedThread()
    {
        if (_maxPending > 0 && _pending.incrementAndGet() >= _maxPending)
        {
            _pending.decrementAndGet();
            return;
        }

        try
        {
            ReservedThread thread = new ReservedThread();
            _executor.execute(thread);
        }
        catch (Throwable e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ignored", e);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{capacity=%d,threads=%s}",
            getClass().getSimpleName(),
            hashCode(),
            getCapacity(),
            _threads);
    }

    private class ReservedThread implements Runnable
    {
        private final Semaphore _semaphore = new Semaphore(0);
        private volatile Runnable _task;
        private volatile Thread _thread;

        @Override
        public void run()
        {
            _thread = Thread.currentThread();
            try
            {
                _pending.decrementAndGet();

                while (true)
                {
                    int slot = _threads.offer(this);
                    if (LOG.isDebugEnabled())
                        LOG.debug("offered to slot " + slot);

                    if (slot < 0)
                        // no slot available
                        return;

                    if (!isRunning() && _threads.remove(this, slot))
                        return;

                    Runnable task = waitForTask();
                    while (task == null)
                    {
                        if (!isRunning())
                            return;

                        // Shrink if we are already removed or there are other reserved threads;
                        // there is a small chance multiple threads will shrink below minSize.
                        if (getAvailable() > _minSize && _threads.remove(this, slot))
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("{} reservedThread shrank {}", ReservedThreadExecutor.this, this);
                            return;
                        }
                        task = waitForTask();
                    }

                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} reservedThread run {} on {}", ReservedThreadExecutor.this, task, this);
                        task.run();
                    }
                    catch (Throwable t)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} task {} failure", ReservedThreadExecutor.this, task, t);
                    }
                    finally
                    {
                        // clear interrupted status between reserved thread iterations
                        if (Thread.interrupted() && LOG.isDebugEnabled())
                            LOG.debug("{} was interrupted", _thread);
                    }
                }
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} reservedThread {} failure", ReservedThreadExecutor.this, this, t);
            }
            finally
            {
                _thread = null;
            }
        }

        private void wakeup(Runnable task)
        {
            _task = task;
            _semaphore.release();
        }

        private Runnable waitForTask()
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("waiting for task");
                if (_idleTimeoutMs <= 0)
                    _semaphore.acquire();
                else if (!_semaphore.tryAcquire(_idleTimeoutMs, TimeUnit.MILLISECONDS))
                    return null;
                Runnable task = _task;
                _task = null;
                return task;
            }
            catch (Throwable e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("wait failed ", e);
            }
            return null;
        }

        private void stop()
        {
            // If we are stopping, the reserved thread may already have stopped. So just interrupt rather than
            // expect an exchange rendezvous.
            _semaphore.release();
            Thread thread = _thread;
            if (thread != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interrupting thread {} for stop", thread);
                thread.interrupt();
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{thread=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _thread);
        }
    }
}
