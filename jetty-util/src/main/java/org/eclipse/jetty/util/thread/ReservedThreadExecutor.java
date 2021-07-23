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

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
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
public class ReservedThreadExecutor extends AbstractLifeCycle implements TryExecutor, Dumpable
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
    private final Queue<ReservedThread> _threads = new ConcurrentLinkedQueue<>();
    private final SynchronousQueue<Runnable> _queue = new SynchronousQueue<>(false);
    private final AtomicBiInteger _count = new AtomicBiInteger(); // hi=pending; lo=size;

    private ThreadPoolBudget.Lease _lease;
    private long _idleTime = 1L;
    private TimeUnit _idleTimeUnit = TimeUnit.MINUTES;
    private volatile long _lastZero = Long.MAX_VALUE;

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

            if (_queue.offer(STOP))
                _count.add(0, -1);
        }

        _threads.clear();
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

        boolean offered = _queue.offer(task);
        int size = offered ? _count.addAndGetLo(-1) : _count.getLo();
        if (size == 0 && task != STOP)
            startReservedThread();
        return offered;
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

            if (size == 0)
                _lastZero = System.nanoTime();
            if (!_count.compareAndSet(count, pending + 1, size))
                continue;
            if (LOG.isDebugEnabled())
                LOG.debug("{} startReservedThread p={}", this, pending + 1);
            try
            {
                ReservedThread thread = new ReservedThread();
                _threads.add(thread);
                _executor.execute(thread);
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
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("reserved", _threads));
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

    private enum State
    {
        PENDING,
        RESERVED,
        RUNNING,
        IDLE,
        STOPPED
    }

    private class ReservedThread implements Runnable
    {
        // The state and thread are kept only for dumping
        private volatile State _state = State.PENDING;
        private Thread _thread;

        private Runnable reservedWait()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} waiting {}", this, ReservedThreadExecutor.this);

            while (true)
            {
                try
                {
                    // Always poll at some period so we can check the abort flag
                    Runnable task = _idleTime <= 0 ? _queue.poll(30, TimeUnit.SECONDS) : _queue.poll(_idleTime, _idleTimeUnit);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} task={} {}", this, task, ReservedThreadExecutor.this);
                    if (task != null)
                        return task;

                    // Have we idled out?
                    if (_idleTime > 0)
                    {
                        _state = State.IDLE;
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

                    // reduce pending if this thread was pending
                    int pending = AtomicBiInteger.getHi(count) - (_state == State.PENDING ? 1 : 0);
                    int size = AtomicBiInteger.getLo(count);

                    // Exit if excess capacity
                    if (size >= _capacity)
                    {
                        exit = true;
                    }
                    // or it has been too long since we hit zero reserved threads (and thus have too many reserved threads)?
                    else if (size > 0 && _idleTime > 0 && _idleTimeUnit.toNanos(_idleTime) < (System.nanoTime() - _lastZero))
                    {
                        _state = State.IDLE;
                        _lastZero = System.nanoTime(); // reset lastZero to slow shrinkage
                        exit = true;
                    }
                    else
                    {
                        size++;
                    }

                    // Update count for pending and size
                    if (!_count.compareAndSet(count, pending, size))
                        continue;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} was={} exit={} size={}+{} capacity={}", this, _state, exit, pending, size, _capacity);
                    if (exit)
                        break;

                    // Once added to the stack, we must always wait for a job on the _task Queue
                    // and never return early, else we may leave a thread blocked offering a _task.
                    _state = State.RESERVED;
                    Runnable task = reservedWait();

                    // Is the task the STOP poison pill?
                    if (task == STOP)
                    {
                        if (_state != State.IDLE)
                            _state = State.STOPPED;
                        break;
                    }

                    // Run the task
                    try
                    {
                        _state = State.RUNNING;
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
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exited {}", this, ReservedThreadExecutor.this);
                _threads.remove(this);
                _thread = null;
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,thread=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _state,
                _thread);
        }
    }
}
