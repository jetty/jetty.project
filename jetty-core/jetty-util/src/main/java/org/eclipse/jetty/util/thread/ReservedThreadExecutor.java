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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
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
    private final AtomicReferenceArray<ReservedThread> _slots;
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
        _slots = new AtomicReferenceArray<>(reservedThreads(executor, capacity));
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
        return _slots.length();
    }

    /**
     * @return the number of threads available to {@link #tryExecute(Runnable)}
     */
    @ManagedAttribute(value = "available reserved threads", readonly = true)
    public int getAvailable()
    {
        int available = 0;
        for (int i = _slots.length(); i-- > 0;)
            if (_slots.get(i) != null)
                available++;
        return available;
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

        int capacity = getCapacity();
        for (int i = capacity; i-- > 0;)
        {
            ReservedThread reserved = _slots.getAndSet(i, null);
            if (reserved != null)
                reserved.stop();
        }

        Thread.yield();

        for (int i = capacity; i-- > 0;)
        {
            ReservedThread reserved = _slots.getAndSet(i, null);
            if (reserved != null)
                reserved.stop();
        }
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

        int capacity = getCapacity();
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = capacity; i-- > 0;)
        {
            ReservedThread reserved = _slots.getAndSet(index, null);
            if (reserved != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} tryExecute wakeup {} for {}", this, reserved, task);
                return reserved.wakeup(task);
            }
            if (++index == capacity)
                index = 0;
        }

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
        int capacity = getCapacity();
        List<Object> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++)
            slots.add(_slots.get(i));
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("slots", slots));
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
            int capacity = getCapacity();
            int index = (int)(Thread.currentThread().getId() % capacity);
            try
            {
                while (isRunning())
                {
                    int slot = -1;
                    // find a slot
                    for (int i = capacity; i-- > 0; )
                    {
                        if (_slots.compareAndSet(index, null, this))
                        {
                            slot = index;
                            break;
                        }
                        if (++index == capacity)
                            index = 0;
                    }

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
                        for (int i = capacity; i-- > 0; )
                        {
                            ReservedThread r = _slots.get(i);
                            if (r != null && r != this)
                            {
                                if (_slots.compareAndSet(slot, this, null))
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("{} reservedThread shrank {}", ReservedThreadExecutor.this, this);
                                    return;
                                }
                            }
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
