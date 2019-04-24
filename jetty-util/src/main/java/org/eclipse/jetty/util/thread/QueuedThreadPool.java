//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.AtomicWords;
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

@ManagedObject("A thread pool")
public class QueuedThreadPool extends ContainerLifeCycle implements SizedThreadPool, Dumpable, TryExecutor
{
    private static final Logger LOG = Log.getLogger(QueuedThreadPool.class);

    private final AtomicWords _counts = new AtomicWords();
    private final AtomicLong _lastShrink = new AtomicLong();
    private final Set<Thread> _threads = ConcurrentHashMap.newKeySet();
    private final Object _joinLock = new Object();
    private final BlockingQueue<Runnable> _jobs;
    private final ThreadGroup _threadGroup;
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

    public QueuedThreadPool(@Name("maxThreads") int maxThreads,  @Name("minThreads") int minThreads)
    {
        this(maxThreads, minThreads, 60000);
    }

    public QueuedThreadPool(@Name("maxThreads") int maxThreads,  @Name("minThreads") int minThreads, @Name("idleTimeout")int idleTimeout)
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
    
    public QueuedThreadPool(@Name("maxThreads") int maxThreads, @Name("minThreads") int minThreads, @Name("idleTimeout") int idleTimeout, @Name("reservedThreads") int reservedThreads, @Name("queue") BlockingQueue<Runnable> queue, @Name("threadGroup") ThreadGroup threadGroup)
    {
        if (maxThreads < minThreads) {
            throw new IllegalArgumentException("max threads ("+maxThreads+") less than min threads ("
                    +minThreads+")");
        }

        setMinThreads(minThreads);
        setMaxThreads(maxThreads);
        setIdleTimeout(idleTimeout);
        setStopTimeout(5000);
        setReservedThreads(reservedThreads);
        if (queue==null)
        {
            int capacity=Math.max(_minThreads, 8);
            queue=new BlockingArrayQueue<>(capacity, capacity);
        }
        _jobs=queue;
        _threadGroup=threadGroup;
        setThreadPoolBudget(new ThreadPoolBudget(this));
    }

    @Override
    public ThreadPoolBudget getThreadPoolBudget()
    {
        return _budget;
    }

    public void setThreadPoolBudget(ThreadPoolBudget budget)
    {
        if (budget!=null && budget.getSizedThreadPool()!=this)
            throw new IllegalArgumentException();
        _budget = budget;
    }

    @Override
    protected void doStart() throws Exception
    {
        _tryExecutor = _reservedThreads==0 ? NO_TRY : new ReservedThreadExecutor(this,_reservedThreads);
        addBean(_tryExecutor);
        
        super.doStart();
        _counts.set(0,0,0,0);
        ensureThreads();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);

        removeBean(_tryExecutor);
        _tryExecutor = TryExecutor.NO_TRY;
        
        super.doStop();

        long timeout = getStopTimeout();
        BlockingQueue<Runnable> jobs = getQueue();

        // If no stop timeout, clear job queue
        if (timeout <= 0)
            jobs.clear();

        // Fill job Q with noop jobs to wakeup idle
        Runnable noop = () -> {};
        for (int i = _counts.getWord0(); i-- > 0; )
            jobs.offer(noop);

        // try to let jobs complete naturally for half our stop time
        joinThreads(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);

        // If we still have threads running, get a bit more aggressive

        // interrupt remaining threads
        for (Thread thread : _threads)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Interrupting {}", thread);
            thread.interrupt();
        }

        // wait again for the other half of our stop time
        joinThreads(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) / 2);

        Thread.yield();
        int size = _threads.size();
        if (size > 0)
        {
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
                    LOG.warn("{} Couldn't stop {}",this,unstopped);
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
            else if (job != noop)
                LOG.warn("Stopped without executing or closing {}", job);
        }

        if (_budget!=null)
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
     * Thread Pool should use Daemon Threading. 
     *
     * @param daemon true to enable delegation
     * @see Thread#setDaemon(boolean)
     */
    public void setDaemon(boolean daemon)
    {
        _daemon = daemon;
    }

    /**
     * Set the maximum thread idle time.
     * Threads that are idle for longer than this period may be
     * stopped.
     *
     * @param idleTimeout Max idle time in ms.
     * @see #getIdleTimeout
     */
    public void setIdleTimeout(int idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    /**
     * Set the maximum number of threads.
     *
     * @param maxThreads maximum number of threads.
     * @see #getMaxThreads
     */
    @Override
    public void setMaxThreads(int maxThreads)
    {
        if (_budget!=null)
            _budget.check(maxThreads);
        _maxThreads = maxThreads;
        if (_minThreads > _maxThreads)
            _minThreads = _maxThreads;
    }

    /**
     * Set the minimum number of threads.
     *
     * @param minThreads minimum number of threads
     * @see #getMinThreads
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
     * Set the number of reserved threads.
     *
     * @param reservedThreads number of reserved threads or -1 for heuristically determined 
     * @see #getReservedThreads
     */
    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _reservedThreads = reservedThreads;
    }

    /**
     * @param name Name of this thread pool to use when naming threads.
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException("started");
        _name = name;
    }

    /**
     * Set the priority of the pool threads.
     *
     * @param priority the new thread priority.
     */
    public void setThreadsPriority(int priority)
    {
        _priority = priority;
    }

    /**
     * Get the maximum thread idle time.
     *
     * @return Max idle time in ms.
     * @see #setIdleTimeout
     */
    @ManagedAttribute("maximum time a thread may be idle in ms")
    public int getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * Get the maximum number of threads.
     *
     * @return maximum number of threads.
     * @see #setMaxThreads
     */
    @Override
    @ManagedAttribute("maximum number of threads in the pool")
    public int getMaxThreads()
    {
        return _maxThreads;
    }

    /**
     * Get the minimum number of threads.
     *
     * @return minimum number of threads.
     * @see #setMinThreads
     */
    @Override
    @ManagedAttribute("minimum number of threads in the pool")
    public int getMinThreads()
    {
        return _minThreads;
    }

    /**
     * Get the number of reserved threads.
     *
     * @return number of reserved threads or or -1 for heuristically determined
     * @see #setReservedThreads
     */
    @ManagedAttribute("the number of reserved threads in the pool")
    public int getReservedThreads()
    {
        if (isStarted())
            return getBean(ReservedThreadExecutor.class).getCapacity();
        return _reservedThreads;
    }

    /**
     * @return The name of the this thread pool
     */
    @ManagedAttribute("name of the thread pool")
    public String getName()
    {
        return _name;
    }

    /**
     * Get the priority of the pool threads.
     *
     * @return the priority of the pool threads.
     */
    @ManagedAttribute("priority of threads in the pool")
    public int getThreadsPriority()
    {
        return _priority;
    }
    
    /**
     * Get the size of the job queue.
     * 
     * @return Number of jobs queued waiting for a thread
     */
    @ManagedAttribute("size of the job queue")
    public int getQueueSize()
    {
        return _jobs.size();
    }

    /**
     * @return whether this thread pool is using daemon threads
     * @see Thread#setDaemon(boolean)
     */
    @ManagedAttribute("thread pool uses daemon threads")
    public boolean isDaemon()
    {
        return _daemon;
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
    public void execute(Runnable job)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("queue {}",job);
        if (!isRunning() || !_jobs.offer(job))
        {
            LOG.warn("{} rejected {}", this, job);
            throw new RejectedExecutionException(job.toString());
        }

        // Make sure there is at least one thread executing the job.
        ensureThreads();
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        TryExecutor tryExecutor = _tryExecutor;
        return tryExecutor!=null && tryExecutor.tryExecute(task);
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
                _joinLock.wait();
        }

        while (isStopping())
            Thread.sleep(1);
    }

    /**
     * @return the total number of threads currently in the pool
     */
    @Override
    @ManagedAttribute("number of threads in the pool")
    public int getThreads()
    {
        return _counts.getWord0();
    }

    /**
     * @return the number of idle threads in the pool
     */
    @Override
    @ManagedAttribute("number of idle threads in the pool")
    public int getIdleThreads()
    {
        return _counts.getWord3();
    }

    /**
     * @return the number of busy threads in the pool
     */
    @ManagedAttribute("number of busy threads in the pool")
    public int getBusyThreads()
    {
        int reserved = _tryExecutor instanceof ReservedThreadExecutor ? ((ReservedThreadExecutor)_tryExecutor).getAvailable() : 0;
        return getThreads() - getIdleThreads() - reserved;
    }
    
    /**
     * <p>Returns whether this thread pool is low on threads.</p>
     * <p>The current formula is:</p>
     * <pre>
     * maxThreads - threads + idleThreads - queueSize &lt;= lowThreadsThreshold
     * </pre>
     *
     * @return whether the pool is low on threads
     * @see #getLowThreadsThreshold()
     */
    @Override
    @ManagedAttribute(value = "thread pool is low on threads", readonly = true)
    public boolean isLowOnThreads()
    {
        return getMaxThreads() - getThreads() + getIdleThreads() - getQueueSize() <= getLowThreadsThreshold();
    }

    private void ensureThreads()
    {
        while (isRunning())
        {
            long count = _counts.get();
            int threads = AtomicWords.getWord0(count);
            int starting = AtomicWords.getWord1(count);
            int idle = AtomicWords.getWord3(count);
            int queue = getQueueSize();

            if (threads >= _maxThreads)
                break;
            if (threads >= _minThreads && (starting + idle) >= queue)
                break;
            if (!_counts.compareAndSet(count, threads + 1, starting + 1, 0, idle))
                continue;

            boolean started = false;
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Starting thread {}",this);

                Thread thread = newThread(_runnable);
                thread.setDaemon(isDaemon());
                thread.setPriority(getThreadsPriority());
                thread.setName(_name + "-" + thread.getId());
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
                    _counts.add(-1,-1,0,0);
            }
        }
    }

    protected Thread newThread(Runnable runnable)
    {
        return new Thread(_threadGroup, runnable);
    }

    protected void removeThread(Thread thread)
    {
        _threads.remove(thread);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Object> threads = new ArrayList<>(getMaxThreads());
        for (final Thread thread : _threads)
        {
            final StackTraceElement[] trace = thread.getStackTrace();
            String knownMethod = "";
            for (StackTraceElement t : trace)
            {
                if ("idleJobPoll".equals(t.getMethodName()) && t.getClassName().endsWith("QueuedThreadPool$Runner"))
                {
                    knownMethod = "IDLE ";
                    break;
                }
                
                if ("reservedWait".equals(t.getMethodName()) && t.getClassName().endsWith("ReservedThread"))
                {
                    knownMethod = "RESERVED ";
                    break;
                }
                
                if ("select".equals(t.getMethodName()) && t.getClassName().endsWith("SelectorProducer"))
                {
                    knownMethod = "SELECTING ";
                    break;
                }
                
                if ("accept".equals(t.getMethodName()) && t.getClassName().contains("ServerConnector"))
                {
                    knownMethod = "ACCEPTING ";
                    break;
                }
            }
            final String known = knownMethod;

            if (isDetailedDump())
            {
                threads.add(new Dumpable()
                {
                    @Override
                    public void dump(Appendable out, String indent) throws IOException
                    {
                        if (StringUtil.isBlank(known))
                            Dumpable.dumpObjects(out, indent, String.format("%s %s %s %d", thread.getId(), thread.getName(), thread.getState(), thread.getPriority()), (Object[])trace);
                        else
                            Dumpable.dumpObjects(out, indent, String.format("%s %s %s %s %d", thread.getId(), thread.getName(), known, thread.getState(), thread.getPriority()));
                    }

                    @Override
                    public String dump()
                    {
                        return null;
                    }
                });
            }
            else
            {
                int p=thread.getPriority();
                threads.add(thread.getId() + " " + thread.getName() + " " + known + thread.getState() + " @ " + (trace.length > 0 ? trace[0] : "???") + (p==Thread.NORM_PRIORITY?"":(" prio="+p)));
            }
        }

        if (isDetailedDump())
        {
            List<Runnable> jobs = new ArrayList<>(getQueue());
            dumpObjects(out, indent, new DumpableCollection("threads", threads), new DumpableCollection("jobs", jobs));
        }
        else
        {
            dumpObjects(out, indent, new DumpableCollection("threads", threads));
        }
    }

    @Override
    public String toString()
    {
        long count = _counts.get();
        int threads = AtomicWords.getWord0(count);
        int starting = AtomicWords.getWord1(count);
        int idle = AtomicWords.getWord3(count);
        int queue = getQueueSize();

        return String.format("%s[%s]@%x{%s,%d<=%d<=%d,s=%d,i=%d,r=%d,q=%d}[%s]",
            getClass().getSimpleName(),
            _name,
            hashCode(),
            getState(),
            getMinThreads(),
            threads,
            getMaxThreads(),
            starting,
            idle,
            getReservedThreads(),
            queue,
            _tryExecutor);
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
                    buf.append("  at ").append(element.toString()).append(System.lineSeparator());
                return buf.toString();
            }
        }
        return null;
    }

    private class Runner implements Runnable
    {
        @Override
        public void run()
        {
            boolean idle = false;
            Runnable job = null;

            try
            {
                job = _jobs.poll();
                idle = job==null;
                _counts.add(0,-1,0,idle?1:0);

                while (true)
                {
                    if (job == null)
                    {
                        if (!idle)
                        {
                            idle = true;
                            _counts.add(0,0,0,1);
                        }

                        job = idleJobPoll();

                        if (job == null)
                        {
                            // maybe we should shrink?
                            int size = getThreads();
                            if (size > _minThreads)
                            {
                                long last = _lastShrink.get();
                                long now = System.nanoTime();
                                if (last == 0 || (now - last) > TimeUnit.MILLISECONDS.toNanos(_idleTimeout))
                                {
                                    if (_lastShrink.compareAndSet(last, now))
                                    {
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("shrinking {}", QueuedThreadPool.this);
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    // run job
                    if (job != null)
                    {
                        if (idle)
                        {
                            idle = false;
                            _counts.add(0,0,0,-1);
                        }

                        if (LOG.isDebugEnabled())
                            LOG.debug("run {} in {}", job, QueuedThreadPool.this);
                        runJob(job);
                        if (LOG.isDebugEnabled())
                            LOG.debug("ran {} in {}", job, QueuedThreadPool.this);

                        // Clear interrupted status
                        Thread.interrupted();
                    }

                    if (!isRunning())
                        break;

                    job = _jobs.poll();
                }
            }
            catch (InterruptedException e)
            {
                LOG.ignore(e);
            }
            catch (Throwable e)
            {
                LOG.warn(String.format("Unexpected thread death: %s in %s", job, QueuedThreadPool.this), e);
            }
            finally
            {
                _counts.add(-1,0,0,idle?-1:0);
                removeThread(Thread.currentThread());
                ensureThreads();
            }
        }

        private Runnable idleJobPoll() throws InterruptedException
        {
            if (_idleTimeout <= 0)
                return _jobs.take();
            return _jobs.poll(_idleTimeout, TimeUnit.MILLISECONDS);
        }
    }
}
