//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.eclipse.jetty.util.AtomicBiInteger.getHi;
import static org.eclipse.jetty.util.AtomicBiInteger.getLo;


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
    private static final long DEFAULT_IDLE_TIMEOUT = TimeUnit.MINUTES.toNanos(1);
    private static final Runnable STOP = new Runnable()
    {
        @Override
        public void run()
        {
        }

        @Override
        public String toString()
        {
            return "STOP";
        }
    };

    private final Executor _executor;
    private final int _capacity;
    private final Set<ReservedThread> _threads = ConcurrentHashMap.newKeySet();
    private final SynchronousQueue<Runnable> _queue = new SynchronousQueue<>(false);
    private final AtomicBiInteger _count = new AtomicBiInteger(); // hi=pending; lo=size;
    private final AtomicLong _lastEmptyNanoTime = new AtomicLong(NanoTime.now());
    private ThreadPoolBudget.Lease _lease;
    private long _idleTimeNanos = DEFAULT_IDLE_TIMEOUT;

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
        if (VirtualThreads.isUseVirtualThreads(executor))
            return 0;
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

    @ManagedAttribute(value = "idle timeout in ms", readonly = true)
    public long getIdleTimeoutMs()
    {
        return NANOSECONDS.toMillis(_idleTimeNanos);
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
        _idleTimeNanos =  (idleTime <= 0 || idleTimeUnit == null) ? DEFAULT_IDLE_TIMEOUT : idleTimeUnit.toNanos(idleTime);
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

        // Mark this instance as stopped.
        int size = _count.getAndSetLo(-1);

        // Offer the STOP task to all waiting reserved threads.
        for (int i = 0; i < size; ++i)
        {
            // Yield to wait for any reserved threads that
            // have incremented the size but not yet polled.
            Thread.yield();
            _queue.offer(STOP);
        }

        // Interrupt any reserved thread missed the offer,
        // so they do not wait for the whole idle timeout.
        _threads.stream()
            .filter(ReservedThread::isReserved)
            .map(t -> t._thread)
            .filter(Objects::nonNull)
            .forEach(Thread::interrupt);
        _threads.clear();
        _count.getAndSetHi(0);
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

        // Offer will only succeed if there is a reserved thread waiting
        boolean offered = _queue.offer(task);

        // If the offer succeeded we need to reduce the size, unless it is set to -1 in the meantime
        int size = _count.getLo();
        while (offered && size > 0 && !_count.compareAndSetLo(size, --size))
            size = _count.getLo();

        // If size is 0 and we are not stopping, start a new reserved thread
        if (size == 0 && task != STOP)
            startReservedThread();

        return offered;
    }

    private void startReservedThread()
    {
        while (true)
        {
            long count = _count.get();
            int pending = getHi(count);
            int size = getLo(count);
            if (size < 0 || pending + size >= _capacity)
                return;
            if (size == 0)
                _lastEmptyNanoTime.set(NanoTime.now());
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
            catch (Throwable e)
            {
                _count.add(-1, 0);
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored", e);
            }
            return;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("threads",
                _threads.stream()
                    .filter(ReservedThread::isReserved)
                    .collect(Collectors.toList())));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{reserved=%d/%d,pending=%d}",
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
        private volatile Thread _thread;

        private boolean isReserved()
        {
            return _state == State.RESERVED;
        }

        private Runnable reservedWait()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} waiting {}", this, ReservedThreadExecutor.this);

            // Keep waiting until stopped, tasked or idle
            while (_count.getLo() >= 0)
            {
                try
                {
                    // Always poll at some period as safety to ensure we don't poll forever.
                    Runnable task = _queue.poll(_idleTimeNanos, NANOSECONDS);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} task={} {}", this, task, ReservedThreadExecutor.this);
                    if (task != null)
                        return task;

                    // we have idled out
                    int size = _count.getLo();
                    // decrement size if we have not also been stopped.
                    while (size > 0)
                    {
                        if (_count.compareAndSetLo(size, --size))
                            break;
                        size = _count.getLo();
                    }
                    _state = size >= 0 ? State.IDLE : State.STOPPED;
                    return STOP;
                }
                catch (InterruptedException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("ignored", e);
                }
            }
            _state = State.STOPPED;
            return STOP;
        }

        @Override
        public void run()
        {
            _thread = Thread.currentThread();
            try
            {
                while (true)
                {
                    long count = _count.get();

                    // reduce pending if this thread was pending
                    int pending = getHi(count) - (_state == State.PENDING ? 1 : 0);
                    int size = getLo(count);

                    State next;
                    if (size < 0 || size >= _capacity)
                    {
                        // The executor has stopped or this thread is excess to capacity
                        next = State.STOPPED;
                    }
                    else
                    {
                        long now = NanoTime.now();
                        long lastEmpty = _lastEmptyNanoTime.get();
                        if (size > 0 && _idleTimeNanos < NanoTime.elapsed(lastEmpty, now) && _lastEmptyNanoTime.compareAndSet(lastEmpty, now))
                        {
                            // it has been too long since we hit zero reserved threads, so are "busy" idle
                            next = State.IDLE;
                        }
                        else
                        {
                            // We will become a reserved thread if we can update the count below.
                            next = State.RESERVED;
                            size++;
                        }
                    }

                    // Update count for pending and size
                    if (!_count.compareAndSet(count, pending, size))
                        continue;

                    if (LOG.isDebugEnabled())
                        LOG.debug("{} was={} next={} size={}+{} capacity={}", this, _state, next, pending, size, _capacity);
                    _state = next;
                    if (next != State.RESERVED)
                        break;

                    // We are reserved whilst we are waiting for an offered _task.
                    Runnable task = reservedWait();

                    // Is the task the STOP poison pill?
                    if (task == STOP)
                        break;

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
                    finally
                    {
                        // Clear any interrupted status.
                        Thread.interrupted();
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