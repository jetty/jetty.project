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
import java.util.concurrent.Exchanger;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
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
public class ReservedThreadExecutor extends AbstractLifeCycle implements TryExecutor, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ReservedThreadExecutor.class);

    private final Executor _executor;
    private final ThreadIdCache<ReservedThread> _threads;
    private ThreadPoolBudget.Lease _lease;
    private long _idleTimeoutMs;

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate. If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     */
    public ReservedThreadExecutor(Executor executor, int capacity)
    {
        _executor = executor;
        _threads = new ThreadIdCache<>(reservedThreads(executor, capacity));
        if (LOG.isDebugEnabled())
            LOG.debug("{}", this);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate, If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     * @return the number of reserved threads that would be used by a ReservedThreadExecutor
     * constructed with these arguments.
     */
    private static int reservedThreads(Executor executor, int capacity)
    {
        if (capacity >= 0)
            return capacity;
        if (VirtualThreads.isUseVirtualThreads(executor))
            return 0;
        int cpus = ProcessorUtils.availableProcessors();
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            int threads = ((ThreadPool.SizedThreadPool)executor).getMaxThreads();
            return Math.max(1, Math.min(cpus, threads / 8));
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

        _threads.takeAll().forEach(ReservedThread::stop);
        Thread.yield();
        _threads.takeAll().forEach(ReservedThread::stop);
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
        int capacity = getCapacity();
        if (task == null || capacity == 0)
            return false;

        ReservedThread reserved = _threads.take();;
        if (reserved != null)
            return reserved.wakeup(task);

        startReservedThread();

        if (LOG.isDebugEnabled())
            LOG.debug("{} tryExecute failed for {}", this, task);
        return false;
    }

    private void startReservedThread()
    {
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
        Dumpable.dumpObjects(out, indent, this, _threads);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{capacity=%d}",
            getClass().getSimpleName(),
            hashCode(),
            getCapacity());
    }

    private class ReservedThread implements Runnable
    {
        private final Exchanger<Runnable> _exchanger = new Exchanger<>();
        private volatile Thread _thread;

        @Override
        public void run()
        {
            _thread = Thread.currentThread();

            try
            {
                while (isRunning())
                {
                    int slot = _threads.give(this);

                    if (slot < 0)
                        // no slot available
                        return;

                    Runnable task = waitForTask();
                    while (task == null)
                    {
                        if (!isRunning())
                            return;

                        // shrink if we are already removed or there are other reserved threads.
                        // There is a small chance multiple threads
                        // will iterate at the same time and we will hit 0, but that is not a huge problem.
                        if (getAvailable() >= 2 && _threads.take(this, slot))
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
                        LOG.warn("reserved error", t);
                    }

                }
            }
            catch (Throwable t)
            {
                LOG.warn("reserved threw", t);
            }
            finally
            {
                _thread = null;
            }
        }

        private boolean wakeup(Runnable task)
        {
            try
            {
                if (_idleTimeoutMs <= 0)
                    _exchanger.exchange(task);
                else
                    _exchanger.exchange(task, _idleTimeoutMs, TimeUnit.MILLISECONDS);
                return true;
            }
            catch (Throwable e)
            {
                LOG.warn("exchange failed", e);
            }
            return false;
        }

        private Runnable waitForTask()
        {
            try
            {
                if (_idleTimeoutMs <= 0)
                    return _exchanger.exchange(null);
                return _exchanger.exchange(null, _idleTimeoutMs, TimeUnit.MILLISECONDS);
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
            // If we are stopping, the reserved thread may already have stopped.  So just interrupt rather than
            // expect an exchange rendezvous.
            Thread thread = _thread;
            if (thread != null)
                thread.interrupt();
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
