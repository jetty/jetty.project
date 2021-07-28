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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.eclipse.jetty.util.AtomicBiInteger.getHi;
import static org.eclipse.jetty.util.AtomicBiInteger.getLo;

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
    private final Set<ReservedThread> _threads = ConcurrentHashMap.newKeySet();
    private final SynchronousQueue<Runnable> _queue = new SynchronousQueue<>(false);
    private final AtomicBiInteger _count = new AtomicBiInteger(); // hi=pending; lo=size;

    private ThreadPoolBudget.Lease _lease;
    private long _idleTimeMs = TimeUnit.MINUTES.toMillis(1);
    private AtomicLong _lastEmptyTime = new AtomicLong(Long.MAX_VALUE);

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

    @ManagedAttribute(value = "idle timeout in ms", readonly = true)
    public long getIdleTimeoutMs()
    {
        return _idleTimeMs;
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
        _idleTimeMs =  (idleTime < 0 || idleTimeUnit == null) ? -1 : idleTimeUnit.toMillis(idleTime);
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
        for (int i = getLo(_count.getAndSet(-1)); i-- > 0;)
        {
            // yield to wait for any reserved threads that have incremented the size but not yet polled
            Thread.yield();
            _queue.offer(STOP);
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
        int size = _count.getLo();
        while (offered && size > 0 && !_count.compareAndSetLo(size, --size))
            size = _count.getLo();
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
                _lastEmptyTime.set(System.nanoTime());
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
        private volatile Thread _thread;

        private Runnable reservedWait()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} waiting {}", this, ReservedThreadExecutor.this);

            while (true)
            {
                try
                {
                    // Always poll at some period as safety to ensure we don't poll forever.
                    Runnable task = _idleTimeMs <= 0 ? _queue.poll(1, TimeUnit.SECONDS) : _queue.poll(_idleTimeMs, TimeUnit.MILLISECONDS);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} task={} {}", this, task, ReservedThreadExecutor.this);
                    if (task != null)
                        return task;

                    // Has the RTE stopped?
                    if (_count.getLo() < 0)
                    {
                        _state = State.STOPPED;
                        return STOP;
                    }

                    // Have we idled out?
                    if (_idleTimeMs > 0)
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
                        long now = System.nanoTime();
                        long lastEmpty = _lastEmptyTime.get();
                        if (size > 0 && _idleTimeMs > 0 && _idleTimeMs < NANOSECONDS.toMillis(now - lastEmpty) &&
                            _lastEmptyTime.compareAndSet(lastEmpty, now))
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
