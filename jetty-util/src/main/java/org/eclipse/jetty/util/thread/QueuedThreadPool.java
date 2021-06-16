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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;

/**
 * <p>A thread pool with a queue of jobs to execute.</p>
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
 *   <li>{@link #getIdleThreads()} idleThreads} = readyThreads - availableReservedThreads</li>
 * </ul>
 */
@ManagedObject("A thread pool")
public class QueuedThreadPool extends ContainerLifeCycle implements ThreadFactory, SizedThreadPool, Dumpable, TryExecutor
{
    private static final Logger LOG = Log.getLogger(QueuedThreadPool.class);
    private static final Runnable NOOP = () ->
    {
    };

    /**
     * Encodes thread counts:
     * <dl>
     * <dt>Hi</dt><dd>Total thread count or Integer.MIN_VALUE if the pool is stopping</dd>
     * <dt>Lo</dt><dd>Net idle threads == idle threads - job queue size.  Essentially if positive,
     * this represents the effective number of idle threads, and if negative it represents the
     * demand for more threads</dd>
     * </dl>
     */
    private final AtomicBiInteger _counts = new AtomicBiInteger(Integer.MIN_VALUE, 0);
    private final AtomicLong _lastShrink = new AtomicLong();
    private final Set<Thread> _threads = ConcurrentHashMap.newKeySet();
    private final Object _joinLock = new Object();
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

    @Override
    protected void doStart() throws Exception
    {
        if (_reservedThreads == 0)
        {
            _tryExecutor = NO_TRY;
        }
        else
        {
            ReservedThreadExecutor reserved = new ReservedThreadExecutor(this, _reservedThreads);
            reserved.setIdleTimeout(_idleTimeout, TimeUnit.MILLISECONDS);
            _tryExecutor = reserved;
        }
        addBean(_tryExecutor);

        _lastShrink.set(System.nanoTime());

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

        // Fill the job queue with noop jobs to wakeup idle threads.
        for (int i = 0; i < threads; ++i)
            jobs.offer(NOOP);

        // If we have a timeout, try to let jobs complete naturally for half our stop time
        if (timeout > 0)
            joinThreads(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);
        Thread.yield();

        // If we still have threads running, get a bit more aggressive
        // interrupt remaining threads
        for (Thread thread : _threads)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Interrupting {}", thread);
            thread.interrupt();
        }

        // If we have a timeout, wait again for the other half of our stop time
        if (timeout > 0)
        {
            joinThreads(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);
            Thread.yield();
            if (LOG.isDebugEnabled())
            {
                for (Thread unstopped : _threads)
                {
                    StringBuilder dmp = new StringBuilder();
                    for (StackTraceElement element : unstopped.getStackTrace())
                    {
                        dmp.append(System.lineSeparator()).append("\tat ").append(element);
                    }
                    LOG.warn("Couldn't stop {}{}", unstopped, dmp.toString());
                }
            }
            else
            {
                for (Thread unstopped : _threads)
                {
                    LOG.warn("{} Couldn't stop {}", this, unstopped);
                }
            }
        }

        // Close any un-executed jobs
        while (!_jobs.isEmpty())
        {
            Runnable job = _jobs.poll();
            if (job instanceof Closeable)
            {
                try
                {
                    ((Closeable)job).close();
                }
                catch (Throwable t)
                {
                    LOG.warn(t);
                }
            }
            else if (job != NOOP)
            {
                LOG.warn("Stopped without executing or closing {}", job);
            }
        }

        if (_budget != null)
            _budget.reset();

        synchronized (_joinLock)
        {
            _joinLock.notifyAll();
        }
    }

    private void joinThreads(long stopByNanos) throws InterruptedException
    {
        for (Thread thread : _threads)
        {
            long canWait = TimeUnit.NANOSECONDS.toMillis(stopByNanos - System.nanoTime());
            if (LOG.isDebugEnabled())
                LOG.debug("Waiting for {} for {}", thread, canWait);
            if (canWait > 0)
                thread.join(canWait);
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
     * @param reservedThreads number of reserved threads or -1 for heuristically determined
     */
    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _reservedThreads = reservedThreads;
    }

    /**
     * @return the name of the this thread pool
     */
    @ManagedAttribute("name of the thread pool")
    public String getName()
    {
        return _name;
    }

    /**
     * <p>Sets the name of this thread pool, used as a prefix for the thread names.</p>
     *
     * @param name the name of the this thread pool
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
        if (tryExecutor instanceof ReservedThreadExecutor)
        {
            ReservedThreadExecutor reservedThreadExecutor = (ReservedThreadExecutor)tryExecutor;
            return reservedThreadExecutor.getCapacity();
        }
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
        if (tryExecutor instanceof ReservedThreadExecutor)
        {
            ReservedThreadExecutor reservedThreadExecutor = (ReservedThreadExecutor)tryExecutor;
            return reservedThreadExecutor.getAvailable();
        }
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

            // The job will be run by an idle thread when available
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
     * Blocks until the thread pool is {@link LifeCycle#stop stopped}.
     */
    @Override
    public void join() throws InterruptedException
    {
        synchronized (_joinLock)
        {
            while (isRunning())
            {
                _joinLock.wait();
            }
        }

        while (isStopping())
        {
            Thread.sleep(1);
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
            _lastShrink.set(System.nanoTime());
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
                return false;
            long update = AtomicBiInteger.encode(threads + deltaThreads, idle + deltaIdle);
            if (_counts.compareAndSet(encoded, update))
                return true;
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

    /**
     * @return the job queue
     */
    protected BlockingQueue<Runnable> getQueue()
    {
        return _jobs;
    }

    /**
     * @param queue the job queue
     * @deprecated pass the queue to the constructor instead
     */
    @Deprecated
    public void setQueue(BlockingQueue<Runnable> queue)
    {
        throw new UnsupportedOperationException("Use constructor injection");
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
                    buf.append("  at ").append(element.toString()).append(System.lineSeparator());
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

        return String.format("%s[%s]@%x{%s,%d<=%d<=%d,i=%d,r=%d,q=%d}[%s]",
            getClass().getSimpleName(),
            _name,
            hashCode(),
            getState(),
            getMinThreads(),
            threads,
            getMaxThreads(),
            idle,
            getReservedThreads(),
            queue,
            _tryExecutor);
    }

    private class Runner implements Runnable
    {
        private Runnable idleJobPoll(long idleTimeout) throws InterruptedException
        {
            if (idleTimeout <= 0)
                return _jobs.take();
            return _jobs.poll(idleTimeout, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Runner started for {}", QueuedThreadPool.this);

            // We always start idle so the idle count is not adjust when we enter the loop below
            // with no job.  The actual thread and idle counts have already been adjusted accounting
            // for this newly started thread in #execute(Runnable)
            boolean idle = true;
            try
            {
                Runnable job = null;
                while (true)
                {
                    // Adjust the idle count and check we are still running
                    if (!addCounts(0, job == null ? 0 : 1))
                        break;
                    idle = true;

                    try
                    {
                        // Look for an immediately available job
                        job = _jobs.poll();
                        if (job == null)
                        {
                            // No job immediately available maybe we should shrink?
                            long idleTimeout = getIdleTimeout();
                            if (idleTimeout > 0 && getThreads() > _minThreads)
                            {
                                long last = _lastShrink.get();
                                long now = System.nanoTime();
                                if ((now - last) > TimeUnit.MILLISECONDS.toNanos(idleTimeout) && _lastShrink.compareAndSet(last, now))
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("shrinking {}", QueuedThreadPool.this);
                                    break;
                                }
                            }

                            // Wait for a job, only after we have checked if we should shrink
                            job = idleJobPoll(idleTimeout);

                            // If still no job?
                            if (job == null)
                                // continue to try again
                                continue;
                        }

                        // The actual idle count was already adjusted in #execute(Runnable)
                        // Here we just remember we are not idle so that we don't adjust again
                        // in finally below if we exit loop after running this job
                        idle = false;

                        // run job
                        if (LOG.isDebugEnabled())
                            LOG.debug("run {} in {}", job, QueuedThreadPool.this);
                        runJob(job);
                        if (LOG.isDebugEnabled())
                            LOG.debug("ran {} in {}", job, QueuedThreadPool.this);
                    }
                    catch (InterruptedException e)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("interrupted {} in {}", job, QueuedThreadPool.this);
                        LOG.ignore(e);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn(e);
                    }
                    finally
                    {
                        // Clear any interrupted status
                        Thread.interrupted();
                    }
                }
            }
            finally
            {
                Thread thread = Thread.currentThread();
                removeThread(thread);

                // Decrement the total thread count and the idle count if we had no job
                addCounts(-1, idle ? -1 : 0);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exited for {}", thread, QueuedThreadPool.this);

                // There is a chance that we shrunk just as a job was queued for us, so
                // check again if we have sufficient threads to meet demand
                ensureThreads();
            }
        }
    }
}
