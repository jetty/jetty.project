//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An Executor using pre-allocated/reserved Threads from a wrapped Executor.
 * <p>Calls to {@link #execute(Runnable)} on a {@link ReservedThreadExecutor} will either succeed
 * with a Thread immediately being assigned the Runnable task, or fail if no Thread is
 * available.
 * <p>Threads are reserved lazily, with a new reserved thread being allocated from a
 * wrapped {@link Executor} when an execution fails.  If the {@link #setIdleTimeout(long, TimeUnit)}
 * is set to non zero (default 1 minute), then the reserved thread pool will shrink by 1 thread
 * whenever it has been idle for that period.
 */
@ManagedObject("A pool for reserved threads")
public class ReservedThreadExecutor extends AbstractLifeCycle implements TryExecutor
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
    private long _idleTime = 1L;
    private TimeUnit _idleTimeUnit = TimeUnit.MINUTES;

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate. If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and
     * thread pool size.
     */
    public ReservedThreadExecutor(Executor executor, int capacity)
    {
        _executor = executor;
        _capacity = reservedThreads(executor, capacity);
        _stack = new ConcurrentLinkedDeque<>();
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
        int cpus = ProcessorUtils.availableProcessors();
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            int threads = ((ThreadPool.SizedThreadPool)executor).getMaxThreads();
            return Math.max(1, Math.min(cpus, threads / 10));
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
        return _capacity;
    }

    /**
     * @return the number of threads available to {@link #tryExecute(Runnable)}
     */
    @ManagedAttribute(value = "available reserved threads", readonly = true)
    public int getAvailable()
    {
        return _stack.size();
    }

    @ManagedAttribute(value = "pending reserved threads", readonly = true)
    public int getPending()
    {
        return _pending.get();
    }

    @ManagedAttribute(value = "idle timeout in MS", readonly = true)
    public long getIdleTimeoutMs()
    {
        if (_idleTimeUnit == null)
            return 0;
        return _idleTimeUnit.toMillis(_idleTime);
    }

    /**
     * Set the idle timeout for shrinking the reserved thread pool
     *
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
        _lease = ThreadPoolBudget.leaseFrom(getExecutor(), this, _capacity);
        _size.set(0);
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        if (_lease != null)
            _lease.close();

        super.doStop();

        while (true)
        {
            int size = _size.get();
            // If no reserved threads left try setting size to -1 to
            // atomically prevent other threads adding themselves to stack.
            if (size == 0 && _size.compareAndSet(size, -1))
                break;

            ReservedThread thread = _stack.pollFirst();
            if (thread == null)
            {
                // Reserved thread must have incremented size but not yet added itself to queue.
                // We will spin until it is added.
                Thread.yield();
                continue;
            }

            _size.decrementAndGet();
            thread.stop();
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
        if (LOG.isDebugEnabled())
            LOG.debug("{} tryExecute {}", this, task);

        if (task == null)
            return false;

        ReservedThread thread = _stack.pollFirst();
        if (thread == null)
        {
            if (task != STOP)
                startReservedThread();
            return false;
        }

        int size = _size.decrementAndGet();
        if (!thread.offer(task))
            return false;

        if (size == 0 && task != STOP)
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
        catch (RejectedExecutionException e)
        {
            _pending.decrementAndGet();
            LOG.ignore(e);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{s=%d/%d,p=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _size.get(),
            _capacity,
            _pending.get());
    }

    private class ReservedThread implements Runnable
    {
        private final SynchronousQueue<Runnable> _task = new SynchronousQueue<>();
        // How often can the thread miss the offer rendezvous before being taken out of operations
        private final AtomicInteger _reservedLoop = new AtomicInteger(10);
        private boolean _starting = true;
        private volatile Thread _thread;

        public boolean offer(Runnable task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} offer task={} {}", this, task, ReservedThreadExecutor.this);

            try
            {
                // Offer the task to the SynchronousQueue, but without blocking forever
                if (_task.offer(task))
                    return true;

                // TODO #6495 the following is defensive code to try to avoid and debug the issue.
                //      Ultimately it should be removed once more is known about the issue.

                // Try spinning to allow the reserved thread to arrive and then try again
                for (int spin = 1000; spin-- > 0;)
                {
                    Thread.yield(); // Replace with onSpinWait in jetty-10
                    if (_task.offer(task))
                        return true;
                }

                // Was this thread removed by an idle timeout?
                if (_reservedLoop.get() == Integer.MIN_VALUE)
                {
                    LOG.warn("ReservedThread.offer failed #6495 on idle thread: {}", ReservedThreadExecutor.this);
                    return false;
                }

                // Spinning failed, so check how many rendezvous we missed
                if (_reservedLoop.updateAndGet(i -> i > 0 ? i - 1 : i) <= 0)
                {
                    // The reserved thread has missed too many rendezvous, so we will warn with some detailed info
                    // and take the thread out of service.
                    Thread thread = _thread;
                    if (thread == null)
                    {
                        LOG.warn("ReservedThread.offer failed #6495: {}", ReservedThreadExecutor.this);
                    }
                    else
                    {
                        StringBuilder stack = new StringBuilder();
                        for (StackTraceElement frame : thread.getStackTrace())
                            stack.append(System.lineSeparator()).append(" at ").append(frame);

                        LOG.warn("ReservedThread.offer failed #6495: {} {}{}", thread, ReservedThreadExecutor.this, stack);
                    }
                    return false;
                }
            }
            catch (Throwable e)
            {
                LOG.ignore(e);
            }

            // Before putting the thread back in the stack, let's check if it was already there
            if (_stack.contains(this))
            {
                LOG.warn("ReservedThread.offer failed #6495 already in stack: {}", ReservedThreadExecutor.this);
                return false;
            }

            // We failed to offer the task, so put this thread back on the stack in last position to give it time to arrive.
            _size.getAndIncrement();
            _stack.offerLast(this);
            return false;
        }

        public void stop()
        {
            offer(STOP);
        }

        private Runnable reservedWait()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} waiting {}", this, ReservedThreadExecutor.this);

            while (true)
            {
                try
                {
                    // Always poll at some period so we can check the abort flag
                    Runnable task = _idleTime <= 0 ? _task.poll(30, TimeUnit.SECONDS) : _task.poll(_idleTime, _idleTimeUnit);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} task={} {}", this, task, ReservedThreadExecutor.this);
                    if (task != null)
                        return task;

                    // TODO #6495 Have we missed rendezvous too many times?
                    if (_reservedLoop.get() <= 0)
                        return STOP;

                    // Have we timed out?
                    if (_idleTime > 0 && _stack.remove(this))
                    {
                        // TODO #6495 checking if race with remove and take from stack
                        Thread.yield();
                        task = _task.poll(10, TimeUnit.MILLISECONDS);
                        if (task != null)
                        {
                            LOG.warn("ReservedThread.wait failed #6495 offer after remove: {}", ReservedThreadExecutor.this);
                            return task;
                        }

                        // Signal idle death in reserved loop
                        _reservedLoop.set(Integer.MIN_VALUE);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} idle {}", this, ReservedThreadExecutor.this);
                        _size.decrementAndGet();
                        return STOP;
                    }
                }
                catch (InterruptedException e)
                {
                    LOG.ignore(e);
                }
            }
        }

        @Override
        public void run()
        {
            _thread = Thread.currentThread();
            try
            {
                while (true)
                {
                    // test and increment size BEFORE decrementing pending,
                    // so that we don't have a race starting new pending.
                    int size = _size.get();

                    // Are we stopped?
                    if (size < 0)
                        return;

                    // Are we surplus to capacity?
                    if (size >= _capacity)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} size {} > capacity", this, size, _capacity);
                        if (_starting)
                            _pending.decrementAndGet();
                        return;
                    }

                    // If we cannot update size then recalculate
                    if (!_size.compareAndSet(size, size + 1))
                        continue;

                    if (_starting)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} started {}", this, ReservedThreadExecutor.this);
                        _pending.decrementAndGet();
                        _starting = false;
                    }

                    // TODO check for #6495 before putting the thread back in the stack, let's check if it was already there
                    if (_stack.contains(this))
                        LOG.warn("ReservedThread.run #6495 already in stack: {}", ReservedThreadExecutor.this);

                    // Insert ourselves in the stack. Size is already incremented, but
                    // that only effects the decision to keep other threads reserved.
                    _stack.offerFirst(this);

                    // Once added to the stack, we must always wait for a job on the _task Queue
                    // and never return early, else we may leave a thread blocked offering a _task.
                    Runnable task = reservedWait();

                    if (task == STOP)
                        // return on STOP poison pill
                        break;

                    if (task == this)
                        LOG.info("{} running self {}", this, ReservedThreadExecutor.this);

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
                    LOG.debug("{} exited {}", this, ReservedThreadExecutor.this);
            }
            finally
            {
                _thread = null;
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }
}
