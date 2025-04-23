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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A thread pool with a queue of jobs to execute.</p>
 * <p>The queue of jobs should be unbounded, because critical jobs that have been submitted for
 * execution cannot be rejected due to the queue bound limit.
 * The queue might be temporarily full due to a job submission spike.
 * Furthermore, the same HTTP request may be handled by different jobs, and would be non-optimal
 * to reject a job of a request that is already being handled in favor of a job for a new
 * concurrent request that is not yet handled by the application.</p>
 * <p>Jetty components that need threads (such as network acceptors and selector) may lease threads
 * from this thread pool using a {@link ThreadPoolBudget}; these threads are "active" from the point
 * of view of the thread pool, but not available to run <em>transient</em> jobs such as processing
 * an HTTP request or a WebSocket frame.</p>
 * <p>QueuedThreadPool has a {@link ReservedThreadExecutor} which leases threads from this pool,
 * but makes them available as if they are "idle" threads.</p>
 * <p>QueuedThreadPool therefore has the following <em>fundamental</em> values:</p>
 * <ul>
 *   <li>{@link #getThreads() threads}: the current number of threads. These threads may execute
 *   a job (either internal or transient), or may be ready to run (either idle or reserved).
 *   This number may grow or shrink as the thread pool grows or shrinks.</li>
 *   <li>{@link #getReadyThreads() readyThreads}: the current number of threads that are ready to
 *   run transient jobs.
 *   This number may grow or shrink as the thread pool grows or shrinks.</li>
 *   <li>{@link #getLeasedThreads() leasedThreads}: the number of threads that run internal jobs.
 *   This number is typically constant after this thread pool is {@link #start() started}.</li>
 * </ul>
 * <p>Given the definitions above, the most interesting definitions are:</p>
 * <ul>
 *   <li>{@link #getThreads() threads} = {@link #getReadyThreads() readyThreads} + {@link #getLeasedThreads() leasedThreads} + {@link #getUtilizedThreads() utilizedThreads}</li>
 *   <li>readyThreads = {@link #getIdleThreads() idleThreads} + {@link #getAvailableReservedThreads() availableReservedThreads}</li>
 *   <li>{@link #getMaxAvailableThreads() maxAvailableThreads} = {@link #getMaxThreads() maxThreads} - leasedThreads</li>
 *   <li>{@link #getUtilizationRate() utilizationRate} = utilizedThreads / maxAvailableThreads</li>
 * </ul>
 * <p>Other definitions, typically less interesting because they take into account threads that
 * execute internal jobs, or because they don't take into account available reserved threads
 * (that are essentially ready to execute transient jobs), are:</p>
 * <ul>
 *   <li>{@link #getBusyThreads() busyThreads} = utilizedThreads + leasedThreads</li>
 *   <li>{@link #getIdleThreads() idleThreads} = readyThreads - availableReservedThreads</li>
 * </ul>
 */
@ManagedObject("A thread pool")
public class QueuedThreadPool extends ContainerLifeCycle implements ThreadFactory, SizedThreadPool, Dumpable, TryExecutor, VirtualThreads.Configurable
{
    private static final Logger LOG = LoggerFactory.getLogger(QueuedThreadPool.class);
    private static final Runnable NOOP = () ->
    {
    };

    /**
     * Encodes thread counts:
     * <dl>
     * <dt>Hi</dt><dd>Total thread count or Integer.MIN_VALUE if the pool is stopping</dd>
     * <dt>Lo</dt><dd>Net idle threads == idle threads - job queue size.  Essentially if positive,
     * this represents the effective number of idle threads, and if negative it represents the
     * demand for more threads, which is equivalent to the job queue's size.</dd>
     * </dl>
     */
    private final AtomicBiInteger _counts = new AtomicBiInteger(Integer.MIN_VALUE, 0);
    private final AtomicLong _evictThreshold = new AtomicLong();
    private final Set<Thread> _threads = ConcurrentHashMap.newKeySet();
    private final AutoLock.WithCondition _joinLock = new AutoLock.WithCondition();
    private final BlockingQueue<Runnable> _jobs;
    private final ThreadGroup _threadGroup;
    private final ThreadFactory _threadFactory;
    private String _name = "qtp" + hashCode();
    private int _idleTimeout;
    private int _maxThreads;
    private int _minThreads;
    private int _reservedThreads = -1;
    private TryExecutor _tryExecutor = TryExecutor.NO_TRY;
    private int _priority = Thread.NORM_PRIORITY;
    private boolean _daemon = false;
    private boolean _detailedDump = false;
    private int _lowThreadsThreshold = 1;
    private ThreadPoolBudget _budget;
    private long _stopTimeout;
    private Executor _virtualThreadsExecutor;
    private int _maxEvictCount = 1;

    public QueuedThreadPool()
    {
        this(200);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads)
    {
        this(maxThreads, Math.min(8, maxThreads));
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads)
    {
        this(maxThreads, minThreads, 60000);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads, @Name("queue") BlockingQueue<Runnable> queue)
    {
        this(maxThreads, minThreads, 60000, -1, queue, null);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads, @Name("idleTimeout") int idleTimeout)
    {
        this(maxThreads, minThreads, idleTimeout, null);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads, @Name("idleTimeout") int idleTimeout, @Name("queue") BlockingQueue<Runnable> queue)
    {
        this(maxThreads, minThreads, idleTimeout, queue, null);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads, @Name("idleTimeout") int idleTimeout, @Name("queue") BlockingQueue<Runnable> queue, @Name("threadGroup") ThreadGroup threadGroup)
    {
        this(maxThreads, minThreads, idleTimeout, -1, queue, threadGroup);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads,
                            @Name("idleTimeout") int idleTimeout, @Name("reservedThreads") int reservedThreads,
                            @Name("queue") BlockingQueue<Runnable> queue, @Name("threadGroup") ThreadGroup threadGroup)
    {
        this(maxThreads, minThreads, idleTimeout, reservedThreads, queue, threadGroup, null);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads,
                            @Name("idleTimeout") int idleTimeout, @Name("reservedThreads") int reservedThreads,
                            @Name("queue") BlockingQueue<Runnable> queue, @Name("threadGroup") ThreadGroup threadGroup,
                            @Name("threadFactory") ThreadFactory threadFactory)
    {
        if (maxThreads < minThreads)
            throw new IllegalArgumentException("max threads (" + maxThreads + ") less than min threads (" + minThreads + ")");
        setMinThreads(minThreads);
        setMaxThreads(maxThreads);
        setIdleTimeout(idleTimeout);
        setStopTimeout(5000);
        setReservedThreads(reservedThreads);
        if (queue == null)
        {
            int capacity = Math.max(_minThreads, 8) * 1024;
            queue = new BlockingArrayQueue<>(capacity, capacity);
        }
        if (queue.remainingCapacity() != Integer.MAX_VALUE)
            LOG.warn("Detected thread pool queue {} bounded at {} entries, which can lead to unexpected behavior. Use an unbounded queue instead.", queue, queue.remainingCapacity());
        _jobs = queue;
        _threadGroup = threadGroup;
        setThreadPoolBudget(new ThreadPoolBudget(this));
        _threadFactory = threadFactory == null ? this : threadFactory;
    }

    @Override
    public ThreadPoolBudget getThreadPoolBudget()
    {
        return _budget;
    }

    public void setThreadPoolBudget(ThreadPoolBudget budget)
    {
        if (budget != null && budget.getSizedThreadPool() != this)
            throw new IllegalArgumentException();
        updateBean(_budget, budget);
        _budget = budget;
    }

    public void setStopTimeout(long stopTimeout)
    {
        _stopTimeout = stopTimeout;
    }

    public long getStopTimeout()
    {
        return _stopTimeout;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_reservedThreads == 0)
        {
            _tryExecutor = NO_TRY;
        }
        else
        {
            int reserved = ReservedThreadExecutor.reservedThreads(this, _reservedThreads);
            if (reserved == 0)
            {
                _tryExecutor = NO_TRY;
            }
            else
            {
                ReservedThreadExecutor rte = new ReservedThreadExecutor(this, _reservedThreads);
                rte.setIdleTimeout(_idleTimeout, TimeUnit.MILLISECONDS);
                _tryExecutor = rte;
            }
        }
        addBean(_tryExecutor);

        _evictThreshold.set(NanoTime.now());

        super.doStart();
        // The threads count set to MIN_VALUE is used to signal to Runners that the pool is stopped.
        _counts.set(0, 0); // threads, idle
        ensureThreads();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);

        super.doStop();

        removeBean(_tryExecutor);
        _tryExecutor = TryExecutor.NO_TRY;

        // Signal the Runner threads that we are stopping
        int threads = _counts.getAndSetHi(Integer.MIN_VALUE);

        // If stop timeout try to gracefully stop
        long timeout = getStopTimeout();
        BlockingQueue<Runnable> jobs = getQueue();
        if (timeout > 0)
        {
            // Fill the job queue with noop jobs to wakeup idle threads.
            for (int i = 0; i < threads; ++i)
                if (!jobs.offer(NOOP))
                    break;

            // try to let jobs complete naturally for half our stop time
            joinThreads(NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);

            // If we still have threads running, get a bit more aggressive

            // interrupt remaining threads
            for (Thread thread : _threads)
            {
                if (thread == Thread.currentThread())
                    continue;
                if (LOG.isDebugEnabled())
                    LOG.debug("Interrupting {}", thread);
                thread.interrupt();
            }

            // wait again for the other half of our stop time
            joinThreads(NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);

            Thread.yield();

            for (Thread unstopped : _threads)
            {
                if (unstopped == Thread.currentThread())
                    continue;
                String stack = "";
                if (LOG.isDebugEnabled())
                {
                    StringBuilder dmp = new StringBuilder();
                    for (StackTraceElement element : unstopped.getStackTrace())
                        dmp.append(System.lineSeparator()).append("\tat ").append(element);
                    stack = dmp.toString();
                }

                LOG.warn("Couldn't stop {}{}", unstopped, stack);
            }
        }

        // Close any un-executed jobs
        while (true)
        {
            Runnable job = _jobs.poll();
            if (job == null)
                break;
            if (job instanceof Closeable)
            {
                try
                {
                    ((Closeable)job).close();
                }
                catch (Throwable t)
                {
                    LOG.warn("Unable to close job: {}", job, t);
                }
            }
            else if (job != NOOP)
                LOG.warn("Stopped without executing or closing {}", job);
        }

        if (_budget != null)
            _budget.reset();

        try (AutoLock.WithCondition l = _joinLock.lock())
        {
            l.signalAll();
        }
    }

    private void joinThreads(long stopByNanos)
    {
        loop : while (true)
        {
            for (Thread thread : _threads)
            {
                // Don't join ourselves
                if (thread == Thread.currentThread())
                    continue;

                long canWait = NanoTime.millisUntil(stopByNanos);
                if (LOG.isDebugEnabled())
                    LOG.debug("Waiting for {} for {}", thread, canWait);
                if (canWait <= 0)
                    return;

                try
                {
                    thread.join(canWait);
                }
                catch (InterruptedException e)
                {
                    // Don't stop waiting for a join if interrupted
                    continue loop;
                }
            }

            return;
        }
    }

    /**
     * @return the maximum thread idle time in ms
     */
    @ManagedAttribute("maximum time a thread may be idle in ms")
    public int getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>Set the maximum thread idle time in ms.</p>
     * <p>Threads that are idle for longer than this period may be stopped.</p>
     *
     * @param idleTimeout the maximum thread idle time in ms
     */
    public void setIdleTimeout(int idleTimeout)
    {
        _idleTimeout = idleTimeout;
        ReservedThreadExecutor reserved = getBean(ReservedThreadExecutor.class);
        if (reserved != null)
            reserved.setIdleTimeout(idleTimeout, TimeUnit.MILLISECONDS);
    }

    /**
     * @return the maximum number of threads
     */
    @Override
    @ManagedAttribute("maximum number of threads in the pool")
    public int getMaxThreads()
    {
        return _maxThreads;
    }

    /**
     * @param maxThreads the maximum number of threads
     */
    @Override
    public void setMaxThreads(int maxThreads)
    {
        if (_budget != null)
            _budget.check(maxThreads);
        _maxThreads = maxThreads;
        if (_minThreads > _maxThreads)
            _minThreads = _maxThreads;
    }

    /**
     * @return the minimum number of threads
     */
    @Override
    @ManagedAttribute("minimum number of threads in the pool")
    public int getMinThreads()
    {
        return _minThreads;
    }

    /**
     * @param minThreads minimum number of threads
     */
    @Override
    public void setMinThreads(int minThreads)
    {
        _minThreads = minThreads;

        if (_minThreads > _maxThreads)
            _maxThreads = _minThreads;

        if (isStarted())
            ensureThreads();
    }

    /**
     * @return number of reserved threads or -1 for heuristically determined
     */
    @ManagedAttribute("number of configured reserved threads or -1 for heuristic")
    public int getReservedThreads()
    {
        return _reservedThreads;
    }

    /**
     * Set number of reserved threads or -1 for heuristically determined.
     * @param reservedThreads number of reserved threads or -1 for heuristically determined
     */
    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _reservedThreads = reservedThreads;
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
        _name = name;
    }

    /**
     * @return the priority of the pool threads
     */
    @ManagedAttribute("priority of threads in the pool")
    public int getThreadsPriority()
    {
        return _priority;
    }

    /**
     * Set the priority of the pool threads.
     * @param priority the priority of the pool threads
     */
    public void setThreadsPriority(int priority)
    {
        _priority = priority;
    }

    /**
     * @return whether to use daemon threads
     * @see Thread#isDaemon()
     */
    @ManagedAttribute("thread pool uses daemon threads")
    public boolean isDaemon()
    {
        return _daemon;
    }

    /**
     * @param daemon whether to use daemon threads
     * @see Thread#setDaemon(boolean)
     */
    public void setDaemon(boolean daemon)
    {
        _daemon = daemon;
    }

    @ManagedAttribute("reports additional details in the dump")
    public boolean isDetailedDump()
    {
        return _detailedDump;
    }

    public void setDetailedDump(boolean detailedDump)
    {
        _detailedDump = detailedDump;
    }

    @ManagedAttribute("threshold at which the pool is low on threads")
    public int getLowThreadsThreshold()
    {
        return _lowThreadsThreshold;
    }

    public void setLowThreadsThreshold(int lowThreadsThreshold)
    {
        _lowThreadsThreshold = lowThreadsThreshold;
    }

    @Override
    public Executor getVirtualThreadsExecutor()
    {
        return _virtualThreadsExecutor;
    }

    @Override
    public void setVirtualThreadsExecutor(Executor executor)
    {
        try
        {
            VirtualThreads.Configurable.super.setVirtualThreadsExecutor(executor);
            _virtualThreadsExecutor = executor;
        }
        catch (UnsupportedOperationException ignored)
        {
        }
    }

    /**
     * <p>Returns the maximum number of idle threads that are evicted for every idle timeout
     * period, thus shrinking this thread pool towards its {@link #getMinThreads() minimum
     * number of threads}.
     * The default value is {@code 1}.</p>
     * <p>For example, consider a thread pool with {@code minThread=2}, {@code maxThread=20},
     * {@code idleTimeout=5000} and {@code maxEvictCount=3}.
     * Let's assume all 20 threads are executing a task, and they all finish their own tasks
     * at the same time and no more tasks are submitted; then, 3 threads will be evicted,
     * while the other 17 will wait another idle timeout; then another 3 threads will be
     * evicted, and so on until {@code minThreads=2} will be reached.</p>
     *
     * @param evictCount the maximum number of idle threads to evict in one idle timeout period
     */
    public void setMaxEvictCount(int evictCount)
    {
        if (evictCount < 1)
            throw new IllegalArgumentException("Invalid evict count " + evictCount);
        _maxEvictCount = evictCount;
    }

    /**
     * @return the maximum number of idle threads to evict in one idle timeout period
     */
    @ManagedAttribute("maximum number of idle threads to evict in one idle timeout period")
    public int getMaxEvictCount()
    {
        return _maxEvictCount;
    }

    /**
     * @return the number of jobs in the queue waiting for a thread
     */
    @ManagedAttribute("size of the job queue")
    public int getQueueSize()
    {
        // The idle counter encodes demand, which is the effective queue size
        int idle = _counts.getLo();
        return Math.max(0, -idle);
    }

    /**
     * @return the maximum number (capacity) of reserved threads
     * @see ReservedThreadExecutor#getCapacity()
     */
    @ManagedAttribute("maximum number (capacity) of reserved threads")
    public int getMaxReservedThreads()
    {
        TryExecutor tryExecutor = _tryExecutor;
        if (tryExecutor instanceof ReservedThreadExecutor reserved)
            return reserved.getCapacity();
        return 0;
    }

    /**
     * @return the number of available reserved threads
     * @see ReservedThreadExecutor#getAvailable()
     */
    @ManagedAttribute("number of available reserved threads")
    public int getAvailableReservedThreads()
    {
        TryExecutor tryExecutor = _tryExecutor;
        if (tryExecutor instanceof ReservedThreadExecutor reserved)
            return reserved.getAvailable();
        return 0;
    }

    /**
     * <p>The <em>fundamental</em> value that represents the number of threads currently known by this thread pool.</p>
     * <p>This value includes threads that have been leased to internal components, idle threads, reserved threads
     * and threads that are executing transient jobs.</p>
     *
     * @return the number of threads currently known to the pool
     * @see #getReadyThreads()
     * @see #getLeasedThreads()
     */
    @Override
    @ManagedAttribute("number of threads in the pool")
    public int getThreads()
    {
        int threads = _counts.getHi();
        return Math.max(0, threads);
    }

    /**
     * <p>The <em>fundamental</em> value that represents the number of threads ready to execute transient jobs.</p>
     *
     * @return the number of threads ready to execute transient jobs
     * @see #getThreads()
     * @see #getLeasedThreads()
     * @see #getUtilizedThreads()
     */
    @ManagedAttribute("number of threads ready to execute transient jobs")
    public int getReadyThreads()
    {
        return getIdleThreads() + getAvailableReservedThreads();
    }

    /**
     * <p>The <em>fundamental</em> value that represents the number of threads that are leased
     * to internal components, and therefore cannot be used to execute transient jobs.</p>
     *
     * @return the number of threads currently used by internal components
     * @see #getThreads()
     * @see #getReadyThreads()
     */
    @ManagedAttribute("number of threads used by internal components")
    public int getLeasedThreads()
    {
        return getMaxLeasedThreads() - getMaxReservedThreads();
    }

    /**
     * <p>The maximum number of threads that are leased to internal components,
     * as some component may allocate its threads lazily.</p>
     *
     * @return the maximum number of threads leased by internal components
     * @see #getLeasedThreads()
     */
    @ManagedAttribute("maximum number of threads leased to internal components")
    public int getMaxLeasedThreads()
    {
        ThreadPoolBudget budget = _budget;
        return budget == null ? 0 : budget.getLeasedThreads();
    }

    /**
     * <p>The number of idle threads, but without including reserved threads.</p>
     * <p>Prefer {@link #getReadyThreads()} for a better representation of
     * "threads ready to execute transient jobs".</p>
     *
     * @return the number of idle threads but not reserved
     * @see #getReadyThreads()
     */
    @Override
    @ManagedAttribute("number of idle threads but not reserved")
    public int getIdleThreads()
    {
        int idle = _counts.getLo();
        return Math.max(0, idle);
    }

    /**
     * <p>The number of threads executing internal and transient jobs.</p>
     * <p>Prefer {@link #getUtilizedThreads()} for a better representation of
     * "threads executing transient jobs".</p>
     *
     * @return the number of threads executing internal and transient jobs
     * @see #getUtilizedThreads()
     */
    @ManagedAttribute("number of threads executing internal and transient jobs")
    public int getBusyThreads()
    {
        return getThreads() - getReadyThreads();
    }

    /**
     * <p>The number of threads executing transient jobs.</p>
     *
     * @return the number of threads executing transient jobs
     * @see #getReadyThreads()
     */
    @ManagedAttribute("number of threads executing transient jobs")
    public int getUtilizedThreads()
    {
        return getThreads() - getLeasedThreads() - getReadyThreads();
    }

    /**
     * <p>The maximum number of threads available to run transient jobs.</p>
     *
     * @return the maximum number of threads available to run transient jobs
     */
    @ManagedAttribute("maximum number of threads available to run transient jobs")
    public int getMaxAvailableThreads()
    {
        return getMaxThreads() - getLeasedThreads();
    }

    /**
     * <p>The rate between the number of {@link #getUtilizedThreads() utilized threads}
     * and the maximum number of {@link #getMaxAvailableThreads() utilizable threads}.</p>
     * <p>A value of {@code 0.0D} means that the thread pool is not utilized, while a
     * value of {@code 1.0D} means that the thread pool is fully utilized to execute
     * transient jobs.</p>
     *
     * @return the utilization rate of threads executing transient jobs
     */
    @ManagedAttribute("utilization rate of threads executing transient jobs")
    public double getUtilizationRate()
    {
        return (double)getUtilizedThreads() / getMaxAvailableThreads();
    }

    /**
     * <p>Returns whether this thread pool is low on threads.</p>
     * <p>The current formula is:</p>
     * <pre>
     * maxThreads - threads + readyThreads - queueSize &lt;= lowThreadsThreshold
     * </pre>
     *
     * @return whether the pool is low on threads
     * @see #getLowThreadsThreshold()
     */
    @Override
    @ManagedAttribute(value = "thread pool is low on threads", readonly = true)
    public boolean isLowOnThreads()
    {
        return getMaxThreads() - getThreads() + getReadyThreads() - getQueueSize() <= getLowThreadsThreshold();
    }

    @Override
    public void execute(Runnable job)
    {
        // Determine if we need to start a thread, use and idle thread or just queue this job
        int startThread;
        while (true)
        {
            // Get the atomic counts
            long counts = _counts.get();

            // Get the number of threads started (might not yet be running)
            int threads = AtomicBiInteger.getHi(counts);
            if (threads == Integer.MIN_VALUE)
                throw new RejectedExecutionException(job.toString());

            // Get the number of truly idle threads. This count is reduced by the
            // job queue size so that any threads that are idle but are about to take
            // a job from the queue are not counted.
            int idle = AtomicBiInteger.getLo(counts);

            // Start a thread if we have insufficient idle threads to meet demand
            // and we are not at max threads.
            startThread = (idle <= 0 && threads < _maxThreads) ? 1 : 0;

            // Add 1|0 or 0|-1 to counts depending upon the decision to start a thread or not;
            // idle can become negative which means there are queued tasks.
            if (!_counts.compareAndSet(counts, threads + startThread, idle + startThread - 1))
                continue;

            break;
        }

        if (!_jobs.offer(job))
        {
            // reverse our changes to _counts.
            if (addCounts(-startThread, 1 - startThread))
                LOG.warn("{} rejected {}", this, job);
            throw new RejectedExecutionException(job.toString());
        }

        if (LOG.isDebugEnabled())
            LOG.debug("queue {} startThread={}", job, startThread);

        // Start a thread if one was needed
        while (startThread-- > 0)
            startThread();
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        TryExecutor tryExecutor = _tryExecutor;
        return tryExecutor != null && tryExecutor.tryExecute(task);
    }

    /**
     * Blocks until the thread pool is {@link org.eclipse.jetty.util.component.LifeCycle} stopped.
     */
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

    private void ensureThreads()
    {
        while (true)
        {
            long counts = _counts.get();
            int threads = AtomicBiInteger.getHi(counts);
            if (threads == Integer.MIN_VALUE)
                break;

            // If we have less than min threads
            // OR insufficient idle threads to meet demand
            int idle = AtomicBiInteger.getLo(counts);
            if (threads < _minThreads || (idle < 0 && threads < _maxThreads))
            {
                // Then try to start a thread.
                if (_counts.compareAndSet(counts, threads + 1, idle + 1))
                    startThread();
                // Otherwise continue to check state again.
                continue;
            }
            break;
        }
    }

    protected void startThread()
    {
        boolean started = false;
        try
        {
            Thread thread = _threadFactory.newThread(_runnable);
            if (LOG.isDebugEnabled())
                LOG.debug("Starting {}", thread);
            _threads.add(thread);
            // Update the evict threshold to prevent thrashing of newly started threads.
            _evictThreshold.set(NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(_idleTimeout));
            thread.start();
            started = true;
        }
        finally
        {
            if (!started)
                addCounts(-1, -1); // threads, idle
        }
    }

    private boolean addCounts(int deltaThreads, int deltaIdle)
    {
        while (true)
        {
            long encoded = _counts.get();
            int threads = AtomicBiInteger.getHi(encoded);
            int idle = AtomicBiInteger.getLo(encoded);
            if (threads == Integer.MIN_VALUE) // This is a marker that the pool is stopped.
            {
                long update = AtomicBiInteger.encode(threads, idle + deltaIdle);
                if (_counts.compareAndSet(encoded, update))
                    return false;
            }
            else
            {
                long update = AtomicBiInteger.encode(threads + deltaThreads, idle + deltaIdle);
                if (_counts.compareAndSet(encoded, update))
                    return true;
            }
        }
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        return PrivilegedThreadFactory.newThread(() ->
        {
            Thread thread = new Thread(_threadGroup, runnable);
            thread.setDaemon(isDaemon());
            thread.setPriority(getThreadsPriority());
            thread.setName(_name + "-" + thread.getId());
            thread.setContextClassLoader(getClass().getClassLoader());
            return thread;
        });
    }

    protected void removeThread(Thread thread)
    {
        _threads.remove(thread);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Object> threads = new ArrayList<>(getMaxThreads());
        for (Thread thread : _threads)
        {
            StackTraceElement[] trace = thread.getStackTrace();
            String stackTag = getCompressedStackTag(trace);
            String baseThreadInfo = String.format("%s %s tid=%d prio=%d", thread.getName(), thread.getState(), thread.getId(), thread.getPriority());

            if (!StringUtil.isBlank(stackTag))
                threads.add(baseThreadInfo + " " + stackTag);
            else if (isDetailedDump())
                threads.add((Dumpable)(o, i) -> Dumpable.dumpObjects(o, i, baseThreadInfo, (Object[])trace));
            else
                threads.add(baseThreadInfo + " @ " + (trace.length > 0 ? trace[0].toString() : "???"));
        }

        DumpableCollection threadsDump = new DumpableCollection("threads", threads);
        if (isDetailedDump())
            dumpObjects(out, indent, threadsDump, new DumpableCollection("jobs", new ArrayList<>(getQueue())));
        else
            dumpObjects(out, indent, threadsDump);
    }

    private String getCompressedStackTag(StackTraceElement[] trace)
    {
        for (StackTraceElement t : trace)
        {
            if ("idleJobPoll".equals(t.getMethodName()) && t.getClassName().equals(Runner.class.getName()))
                return "IDLE";
            if ("reservedWait".equals(t.getMethodName()) && t.getClassName().endsWith("ReservedThread"))
                return "RESERVED";
            if ("select".equals(t.getMethodName()) && t.getClassName().endsWith("SelectorProducer"))
                return "SELECTING";
            if ("accept".equals(t.getMethodName()) && t.getClassName().contains("ServerConnector"))
                return  "ACCEPTING";
        }
        return "";
    }

    private final Runnable _runnable = new Runner();

    /**
     * <p>Runs the given job in the {@link Thread#currentThread() current thread}.</p>
     * <p>Subclasses may override to perform pre/post actions before/after the job is run.</p>
     *
     * @param job the job to run
     */
    protected void runJob(Runnable job)
    {
        job.run();
    }

    protected void onJobFailure(Throwable x)
    {
        LOG.warn("Job failed", x);
    }

    /**
     * <p>Determines whether to evict the current thread from the pool.</p>
     *
     * @return whether the current thread should be evicted
     */
    protected boolean evict()
    {
        long idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(getIdleTimeout());

        // There is a chance that many threads enter this method concurrently,
        // and if all of them are evicted the pool shrinks below minThreads.
        // For example when minThreads=3, threads=8, maxEvictCount=10 we want
        // to evict at most 5 threads (8-3), not 10.
        // When a thread fails the CAS, it may assume that another thread has
        // been evicted, so the CAS should be attempted only a number of times
        // equal to the most threads we want to evict (5 in the example above).
        int threads = getThreads();
        int minThreads = getMinThreads();
        int threadsToEvict = threads - minThreads;

        while (true)
        {
            if (threadsToEvict > 0)
            {
                // We have excess threads, so check if we should evict the current thread.
                long now = NanoTime.now();
                long evictPeriod = idleTimeoutNanos / getMaxEvictCount();
                if (LOG.isDebugEnabled())
                    LOG.debug("Evict check, period={}ms {}", TimeUnit.NANOSECONDS.toMillis(evictPeriod), this);

                long evictThreshold = _evictThreshold.get();
                long threshold = evictThreshold;

                // If the threshold is too far in the past,
                // advance it to be one idle timeout before now.
                if (NanoTime.elapsed(threshold, now) > idleTimeoutNanos)
                    threshold = now - idleTimeoutNanos;

                // Advance the threshold by one evict period.
                threshold += evictPeriod;

                // Is the new threshold in the future?
                if (NanoTime.isBefore(now, threshold))
                {
                    // Yes - we cannot evict yet, so continue looking for jobs.
                    if (LOG.isDebugEnabled())
                        LOG.debug("Evict skipped, threshold={}ms in the future {}", NanoTime.millisElapsed(now, threshold), this);
                    return false;
                }

                // We can evict if we can update the threshold.
                if (_evictThreshold.compareAndSet(evictThreshold, threshold))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Evicted, threshold={}ms in the past {}", NanoTime.millisElapsed(threshold, now), this);
                    return true;
                }
                else
                {
                    // Some other thread was evicted.
                    --threadsToEvict;
                }
            }
            else
            {
                // No more threads to evict, continue looking for jobs.
                if (LOG.isDebugEnabled())
                    LOG.debug("Evict skipped, no excess threads {}", this);
                return false;
            }
        }
    }

    /**
     * @return the job queue
     */
    protected BlockingQueue<Runnable> getQueue()
    {
        return _jobs;
    }

    /**
     * @param id the thread ID to interrupt.
     * @return true if the thread was found and interrupted.
     */
    @ManagedOperation("interrupts a pool thread")
    public boolean interruptThread(@Name("id") long id)
    {
        for (Thread thread : _threads)
        {
            if (thread.getId() == id)
            {
                thread.interrupt();
                return true;
            }
        }
        return false;
    }

    /**
     * @param id the thread ID to interrupt.
     * @return the stack frames dump
     */
    @ManagedOperation("dumps a pool thread stack")
    public String dumpThread(@Name("id") long id)
    {
        for (Thread thread : _threads)
        {
            if (thread.getId() == id)
            {
                StringBuilder buf = new StringBuilder();
                buf.append(thread.getId()).append(" ").append(thread.getName()).append(" ");
                buf.append(thread.getState()).append(":").append(System.lineSeparator());
                for (StackTraceElement element : thread.getStackTrace())
                {
                    buf.append("  at ").append(element).append(System.lineSeparator());
                }
                return buf.toString();
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        long count = _counts.get();
        int threads = Math.max(0, AtomicBiInteger.getHi(count));
        int idle = Math.max(0, AtomicBiInteger.getLo(count));
        int queue = getQueueSize();

        return String.format("%s[%s]@%x{%s,%d<=%d<=%d,i=%d,r=%d,t=%dms,q=%d}[%s]",
            getClass().getSimpleName(),
            _name,
            hashCode(),
            getState(),
            getMinThreads(),
            threads,
            getMaxThreads(),
            idle,
            getReservedThreads(),
            NanoTime.millisUntil(_evictThreshold.get()),
            queue,
            _tryExecutor);
    }

    private class Runner implements Runnable
    {
        private Runnable idleJobPoll(long idleTimeoutNanos) throws InterruptedException
        {
            if (idleTimeoutNanos <= 0)
                return _jobs.take();
            return _jobs.poll(idleTimeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Runner started for {}", QueuedThreadPool.this);

            boolean idle = true;
            try
            {
                while (_counts.getHi() != Integer.MIN_VALUE)
                {
                    try
                    {
                        long idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(getIdleTimeout());
                        Runnable job = idleJobPoll(idleTimeoutNanos);

                        while (job != null)
                        {
                            idle = false;
                            // Run the jobs.
                            if (LOG.isDebugEnabled())
                                LOG.debug("run {} in {}", job, QueuedThreadPool.this);
                            doRunJob(job);
                            if (LOG.isDebugEnabled())
                                LOG.debug("ran {} in {}", job, QueuedThreadPool.this);

                            // Signal that we are idle again; since execute() subtracts
                            // 1 from idle each time a job is submitted, we have to add
                            // 1 for each executed job here to compensate.
                            if (!addCounts(0, 1))
                                break;
                            idle = true;

                            // Look for another job
                            job = _jobs.poll();
                        }

                        if (evict())
                            break;
                    }
                    catch (InterruptedException e)
                    {
                        LOG.trace("IGNORED", e);
                    }
                }
            }
            finally
            {
                Thread thread = Thread.currentThread();
                removeThread(thread);

                // Decrement the total thread count and the idle count if we had no job.
                addCounts(-1, idle ? -1 : 0);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exited for {}", thread, QueuedThreadPool.this);

                // There is a chance that we shrunk just as a job was queued,
                // or multiple concurrent threads ran out of jobs,
                // so check again if we have sufficient threads to meet demand.
                ensureThreads();
            }
        }

        private void doRunJob(Runnable job)
        {
            try
            {
                runJob(job);
            }
            catch (Throwable e)
            {
                onJobFailure(e);
            }
            finally
            {
                // Clear any thread interrupted status.
                Thread.interrupted();
            }
        }
    }
}
