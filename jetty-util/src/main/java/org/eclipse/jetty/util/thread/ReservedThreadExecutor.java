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

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An Executor using preallocated/reserved Threads from a wrapped Executor.
 * <p>Calls to {@link #execute(Runnable)} on a {@link ReservedThreadExecutor} will either succeed
 * with a Thread immediately being assigned the Runnable task, or fail if no Thread is
 * available.
 * <p>Threads are reserved lazily, with a new reserved thread being allocated from a
 * wrapped {@link Executor} when an execution fails.  If the {@link #setIdleTimeout(long, TimeUnit)}
 * is set to non zero (default 1 minute), then the reserved thread pool will shrink by 1 thread
 * whenever it has been idle for that period.
 */
@ManagedObject("A pool for reserved threads")
public class ReservedThreadExecutor extends AbstractLifeCycle implements Executor
{
    private static final Logger LOG = Log.getLogger(ReservedThreadExecutor.class);
    private static final Runnable STOP = new Runnable()
    {
        @Override
        public void run()
        {
        }

        @Override
        public String toString()
        {
            return "STOP!";
        }
    };

    private final Executor _executor;
    private final int _capacity;
    private final ConcurrentLinkedDeque<ReservedThread> _stack;
    private final AtomicInteger _size = new AtomicInteger();
    private final AtomicInteger _pending = new AtomicInteger();

    private ThreadPoolBudget.Lease _lease;
    private Object _owner;
    private long _idleTime = 1L;
    private TimeUnit _idleTimeUnit = TimeUnit.MINUTES;

    public ReservedThreadExecutor(Executor executor)
    {
        this(executor,1);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate. If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     */
    public ReservedThreadExecutor(Executor executor, int capacity)
    {
        this(executor,capacity,null);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate. If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     */
    public ReservedThreadExecutor(Executor executor,int capacity, Object owner)
    {
        _executor = executor;
        _capacity = reservedThreads(executor,capacity);
        _stack = new ConcurrentLinkedDeque<>();
        _owner = owner;

        LOG.debug("{}",this);
    }
    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate, If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     * @return the number of reserved threads that would be used by a ReservedThreadExecutor
     * constructed with these arguments.
     */
    public static int reservedThreads(Executor executor,int capacity)
    {
        if (capacity>=0)
            return capacity;
        int cpus = Runtime.getRuntime().availableProcessors();
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

    @ManagedAttribute(value = "max number of reserved threads", readonly = true)
    public int getCapacity()
    {
        return _capacity;
    }

    @ManagedAttribute(value = "available reserved threads", readonly = true)
    public int getAvailable()
    {
        return _size.get();
    }

    @ManagedAttribute(value = "pending reserved threads", readonly = true)
    public int getPending()
    {
        return _pending.get();
    }

    @ManagedAttribute(value = "idletimeout in MS", readonly = true)
    public long getIdleTimeoutMs()
    {
        if(_idleTimeUnit==null)
            return 0;
        return _idleTimeUnit.toMillis(_idleTime);
    }

    /**
     * Set the idle timeout for shrinking the reserved thread pool
     * @param idleTime Time to wait before shrinking, or 0 for no timeout.
     * @param idleTimeUnit Time units for idle timeout
     */
    public void setIdleTimeout(long idleTime, TimeUnit idleTimeUnit)
    {
        if (isRunning())
            throw new IllegalStateException();
        _idleTime = idleTime;
        _idleTimeUnit = idleTimeUnit;
    }

    @Override
    public void doStart() throws Exception
    {
        _lease = ThreadPoolBudget.leaseFrom(getExecutor(),this,_capacity);
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        if (_lease!=null)
            _lease.close();
        while(true)
        {
            ReservedThread thread = _stack.pollFirst();
            if (thread == null)
                break;
            _size.decrementAndGet();
            thread.stop();
        }
        super.doStop();
    }

    @Override
    public void execute(Runnable task) throws RejectedExecutionException
    {
        if (!tryExecute(task))
            throw new RejectedExecutionException();
    }

    /**
     * @param task The task to run
     * @return True iff a reserved thread was available and has been assigned the task to run.
     */
    public boolean tryExecute(Runnable task)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} tryExecute {}",this ,task);

        if (task==null)
            return false;

        ReservedThread thread = _stack.pollFirst();
        if (thread==null)
        {
            if (task!=STOP)
                startReservedThread();
            return false;
        }

        int size = _size.decrementAndGet();
        thread.offer(task);

        if (size==0 && task!=STOP)
            startReservedThread();

        return true;
    }

    private void startReservedThread()
    {
        try
        {
            while (true)
            {
                // Not atomic, but there is a re-check in ReservedThread.run().
                int pending = _pending.get();
                int size = _size.get();
                if (pending + size >= _capacity)
                    return;
                if (_pending.compareAndSet(pending, pending + 1))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} startReservedThread p={}", this, pending + 1);
                    _executor.execute(new ReservedThread());
                    return;
                }
            }
        }
        catch(RejectedExecutionException e)
        {
            LOG.ignore(e);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{s=%d/%d,p=%d}@%s",
                getClass().getSimpleName(),
                hashCode(),
                _size.get(),
                _capacity,
                _pending.get(),
                _owner);
    }

    private class ReservedThread implements Runnable
    {
        private final Locker _locker = new Locker();
        private final Condition _wakeup = _locker.newCondition();
        private boolean _starting = true;
        private Runnable _task = null;

        public void offer(Runnable task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} offer {}", this, task);

            try (Locker.Lock lock = _locker.lock())
            {
                _task = task;
                _wakeup.signal();
            }
        }

        public void stop()
        {
            offer(STOP);
        }

        private Runnable reservedWait()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} waiting", this);

            Runnable task = null;
            while (task==null)
            {
                boolean idle = false;

                try (Locker.Lock lock = _locker.lock())
                {
                    if (_task == null)
                    {
                        try
                        {
                            if (_idleTime == 0)
                                _wakeup.await();
                            else
                                idle = !_wakeup.await(_idleTime, _idleTimeUnit);
                        }
                        catch (InterruptedException e)
                        {
                            LOG.ignore(e);
                        }
                    }
                    task = _task;
                    _task = null;
                }

                if (idle)
                {
                    // Because threads are held in a stack, excess threads will be
                    // idle.  However, we cannot remove threads from the bottom of
                    // the stack, so we submit a poison pill job to stop the thread
                    // on top of the stack (which unfortunately will be the most
                    // recently used)
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} IDLE", this);
                    tryExecute(STOP);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} task={}", this, task);

            return task;
        }

        @Override
        public void run()
        {
            while (isRunning())
            {
                // test and increment size BEFORE decrementing pending,
                // so that we don't have a race starting new pending.
                while(true)
                {
                    int size = _size.get();
                    if (size>=_capacity)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} size {} > capacity", this, size, _capacity);
                        if (_starting)
                            _pending.decrementAndGet();
                        return;
                    }
                    if (_size.compareAndSet(size,size+1))
                        break;
                }

                if (_starting)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} started", this);
                    _pending.decrementAndGet();
                    _starting = false;
                }

                // Insert ourselves in the stack. Size is already incremented, but
                // that only effects the decision to keep other threads reserved.
                _stack.offerFirst(this);

                // Wait for a task
                Runnable task = reservedWait();

                if (task==STOP)
                    // return on STOP poison pill
                    break;

                // Run the task
                try
                {
                    task.run();
                }
                catch (Throwable e)
                {
                    LOG.warn(e);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} Exited", this);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x",ReservedThreadExecutor.this,hashCode());
        }
    }
}
