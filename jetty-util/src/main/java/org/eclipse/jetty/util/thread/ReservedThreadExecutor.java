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

import org.eclipse.jetty.util.AtomicBiInteger;
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
    private final AtomicBiInteger _count = new AtomicBiInteger(); // hi=pending; lo=size;

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
        return _count.getLo();
    }

    @ManagedAttribute(value = "pending reserved threads", readonly = true)
    public int getPending()
    {
        return _count.getHi();
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
        _count.set(0, 0);
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
            // If no reserved threads left try setting size to MAX_VALUE to
            // atomically prevent other threads adding themselves to stack.
            if (_count.compareAndSetLo(0, Integer.MAX_VALUE))
                break;

            // Are we already stopped?
            if (_count.getLo() == Integer.MAX_VALUE)
                break;

            ReservedThread thread = _stack.pollFirst();
            if (thread == null)
            {
                // Reserved thread must have incremented size but not yet added itself to queue.
                // We will spin until it is added.
                Thread.yield();
                continue;
            }

            _count.add(0, -1);
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

        int size = _count.addAndGetLo(-1);
        if (!thread.offer(task))
            return false;

        if (size == 0 && task != STOP)
            startReservedThread();

        return true;
    }

    private void startReservedThread()
    {
        while (true)
        {
            long count = _count.get();
            int pending = AtomicBiInteger.getHi(count);
            int size = AtomicBiInteger.getLo(count);
            if (size == Integer.MAX_VALUE || pending + size >= _capacity)
                return;

            if (!_count.compareAndSet(count, pending + 1, size))
                continue;
            if (LOG.isDebugEnabled())
                LOG.debug("{} startReservedThread p={}", this, pending + 1);
            try
            {
                _executor.execute(new ReservedThread());
            }
            catch (RejectedExecutionException e)
            {
                _count.add(-1, 0);
                LOG.ignore(e);
            }
            return;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{s=%d/%d,p=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _count.getLo(),
            _capacity,
            _count.getHi());
    }

    private class ReservedThread implements Runnable
    {
        private static final int MAX_CONSECUTIVE_MISSES = 10;
        private final SynchronousQueue<Runnable> _task = new SynchronousQueue<>();

        // Reserved Thread state is an integer that is either one of the special values
        // below; 0 for running normally or a small positive value that counts the number
        // of consecutive offer misses.
        private static final int PENDING = Integer.MAX_VALUE;
        private static final int RUNNING = 0;
        private static final int STOPPED = -1;
        private static final int MISSED_OUT = -2;
        private static final int IDLED_OUT = -3;
        private final AtomicInteger _state = new AtomicInteger(PENDING);
        private Thread _thread;

        public boolean offer(Runnable task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} offer task={} {}", this, task, ReservedThreadExecutor.this);

            try
            {
                // Offer the task to the SynchronousQueue, but without blocking or even yielding.
                // The intent of RTE is to support EPC strategy, thus if we block or even yield here then we give
                // an opportunity for the caller to switch CPUs, defeating the intent of EPC.
                if (_task.offer(task))
                    return true;

                // check state and potential reason for the miss
                while (true)
                {
                    int state = _state.get();
                    // Are we not in a state that should have been offered to?
                    if (state < 0 || state == PENDING)
                    {
                        LOG.warn("ReservedThread.offered to: {}", this);
                        return false;
                    }

                    // Have we missed too often?
                    if (state >= MAX_CONSECUTIVE_MISSES)
                    {
                        if (_state.compareAndSet(state, MISSED_OUT))
                            continue;
                        LOG.warn("ReservedThread.offer max missed: {}", this);
                        return false;
                    }

                    // increment the misses
                    if (_state.compareAndSet(state, state + 1))
                        break;
                }
            }
            catch (Throwable e)
            {
                LOG.warn(e);
            }

            // We failed to offer the task, so put this thread back on the stack in last position to give it time to arrive.
            if (LOG.isDebugEnabled())
                LOG.debug("{} offer missed {}", this, task, ReservedThreadExecutor.this);
            _count.add(0, 1);
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

                    // Check non running states?
                    int state = _state.get();
                    if (state < 0)
                        return STOP;

                    // Have we idled out?
                    if (_idleTime > 0 && _stack.remove(this))
                    {
                        if (!_state.compareAndSet(state, IDLED_OUT))
                            LOG.warn("Bad idle state: {} in {}", this, ReservedThreadExecutor.this);
                        else if (LOG.isDebugEnabled())
                            LOG.debug("Idle {} in {}", this, ReservedThreadExecutor.this);
                        _count.add(0, -1);
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
                    boolean exit = false;
                    long count = _count.get();

                    // check the state
                    int state = _state.get();
                    if (state < 0)
                        break;

                    // reduce pending if this thread was pending
                    int pending = AtomicBiInteger.getHi(count) - (state == PENDING ? 1 : 0);

                    // increment size if not stopped nor surplus to capacity?
                    int size = AtomicBiInteger.getLo(count);
                    if (size < _capacity)
                        size++;
                    else
                        exit = true;

                    // Update count for pending and size
                    if (!_count.compareAndSet(count, pending, size))
                        continue;
                    // Update state (clearing pending and resetting consecutive miss count)
                    if (!_state.compareAndSet(state, RUNNING))
                        throw new IllegalStateException("Bad State: " + this);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} was={} exit={} size={}+{} capacity={}", this, state, exit, pending, size, _capacity);
                    if (exit)
                        break;

                    // Insert ourselves in the stack.
                    _stack.offerFirst(this);

                    // Once added to the stack, we must always wait for a job on the _task Queue
                    // and never return early, else we may leave a thread blocked offering a _task.
                    Runnable task = reservedWait();

                    // Is the task the STOP poison pill?
                    if (task == STOP)
                        break;

                    // Run the task
                    try
                    {
                        task.run();
                    }
                    catch (Throwable e)
                    {
                        LOG.warn("Unable to run task", e);
                    }
                }
            }
            finally
            {
                int state = _state.get();
                if ((state < 0 && state != IDLED_OUT) || !_state.compareAndSet(state, STOPPED))
                    LOG.warn("{} exited {}", this, ReservedThreadExecutor.this);
                else if (LOG.isDebugEnabled())
                    LOG.debug("{} exited {}", this, ReservedThreadExecutor.this);
                _thread = null;
            }
        }

        @Override
        public String toString()
        {
            String s;
            int state = _state.get();
            switch (state)
            {
                case PENDING:
                    s = "PENDING";
                    break;
                case RUNNING:
                    s = "RUNNING";
                    break;
                case IDLED_OUT:
                    s = "IDLED_OUT";
                    break;
                case MISSED_OUT:
                    s = "MISSED_OUT";
                    break;
                default:
                    s = "MISSED_" + state;
                    break;
            }
            return String.format("%s@%x{%s,thread=%s}",
                getClass().getSimpleName(),
                hashCode(),
                s,
                _thread);
        }
    }
}
