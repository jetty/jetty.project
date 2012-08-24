//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;

@ManagedObject("A thread pool with no max bound by default")
public class QueuedThreadPool extends AbstractLifeCycle implements SizedThreadPool, Dumpable
{
    private static final Logger LOG = Log.getLogger(QueuedThreadPool.class);

    private final AtomicInteger _threadsStarted = new AtomicInteger();
    private final AtomicInteger _threadsIdle = new AtomicInteger();
    private final AtomicLong _lastShrink = new AtomicLong();
    private final ConcurrentLinkedQueue<Thread> _threads=new ConcurrentLinkedQueue<>();
    private final Object _joinLock = new Object();
    private BlockingQueue<Runnable> _jobs;
    private String _name;
    private int _maxIdleTimeMs=60000;
    private int _maxThreads;
    private int _minThreads;
    private int _maxQueued=-1;
    private int _priority=Thread.NORM_PRIORITY;
    private boolean _daemon=false;
    private boolean _detailedDump=false;

    public QueuedThreadPool()
    {
        this(200,8,60000);
    }

    public QueuedThreadPool(int maxThreads)
    {
        this(maxThreads,8,60000);
    }

    public QueuedThreadPool(int maxThreads, int minThreads)
    {
        this(maxThreads,minThreads,60000);
    }

    public QueuedThreadPool(int maxThreads, int minThreads, int maxIdleTimeMs)
    {
        _name="qtp"+super.hashCode();
        setMinThreads(minThreads);
        setMaxThreads(maxThreads);
        setMaxIdleTimeMs(maxIdleTimeMs);
        setStopTimeout(5000);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _threadsStarted.set(0);

        if (_jobs==null)
        {
            int maxQueued = getMaxQueued();
            _jobs=maxQueued>0 ?new ArrayBlockingQueue<Runnable>(maxQueued)
                :new BlockingArrayQueue<Runnable>(_minThreads,_minThreads);
        }

        int threads=_threadsStarted.get();
        while (isRunning() && threads<_minThreads)
        {
            startThread(threads);
            threads=_threadsStarted.get();
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        long start=System.currentTimeMillis();

        long timeout=getStopTimeout();
        BlockingQueue<Runnable> jobs = getQueue();
        
        // If no stop timeout, clear job queue
        if (timeout<=0)
            jobs.clear();
        
        // Fill job Q with noop jobs to wakeup idle 
        Runnable noop = new Runnable(){@Override public void run(){}};
        for (int i=_threadsStarted.get();i-->0;)
            jobs.offer(noop);
        
        // try to jobs complete naturally for half our stop time
        long stopby=System.currentTimeMillis()+timeout/2;
        for (Thread thread : _threads)
        {
            long canwait =stopby-System.currentTimeMillis();
            if (canwait>0)
                thread.join(canwait);
        }
        
        // If we still have threads running, get a bit more aggressive

        // interrupt remaining threads
        if (_threadsStarted.get()>0)
            for (Thread thread : _threads)
                thread.interrupt();
        
        // wait again for the other half of our stop time
        stopby=System.currentTimeMillis()+timeout/2;
        for (Thread thread : _threads)
        {
            long canwait =stopby-System.currentTimeMillis();
            if (canwait>0)
                thread.join(canwait);
        }
        
        Thread.yield();
        int size=_threads.size();
        if (size>0)
        {
            LOG.warn("{} threads could not be stopped", size);

            if ((size<=Runtime.getRuntime().availableProcessors()) || LOG.isDebugEnabled())
            {
                for (Thread unstopped : _threads)
                {
                    StringBuilder dmp = new StringBuilder();
                    for (StackTraceElement element : unstopped.getStackTrace())
                    {
                        dmp.append(StringUtil.__LINE_SEPARATOR).append("\tat ").append(element);
                    }
                    LOG.warn("Couldn't stop {}{}", unstopped, dmp.toString());
                }
            }
        }

        synchronized (_joinLock)
        {
            _joinLock.notifyAll();
        }
    }

    /**
     * Delegated to the named or anonymous Pool.
     */
    public void setDaemon(boolean daemon)
    {
        _daemon=daemon;
    }

    /** Set the maximum thread idle time.
     * Threads that are idle for longer than this period may be
     * stopped.
     * Delegated to the named or anonymous Pool.
     * @see #getMaxIdleTimeMs
     * @param maxIdleTimeMs Max idle time in ms.
     */
    public void setMaxIdleTimeMs(int maxIdleTimeMs)
    {
        _maxIdleTimeMs=maxIdleTimeMs;
    }

    /** Set the maximum number of threads.
     * Delegated to the named or anonymous Pool.
     * @see #getMaxThreads
     * @param maxThreads maximum number of threads.
     */
    @Override
    public void setMaxThreads(int maxThreads)
    {
        _maxThreads=maxThreads;
        if (_minThreads>_maxThreads)
            _minThreads=_maxThreads;
    }

    /** Set the minimum number of threads.
     * Delegated to the named or anonymous Pool.
     * @see #getMinThreads
     * @param minThreads minimum number of threads
     */
    @Override
    public void setMinThreads(int minThreads)
    {
        _minThreads=minThreads;

        if (_minThreads>_maxThreads)
            _maxThreads=_minThreads;

        int threads=_threadsStarted.get();
        while (isStarted() && threads<_minThreads)
        {
            startThread(threads);
            threads=_threadsStarted.get();
        }
    }

    /**
     * @param name Name of the BoundedThreadPool to use when naming Threads.
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException("started");
        _name= name;
    }

    /** Set the priority of the pool threads.
     *  @param priority the new thread priority.
     */
    public void setThreadsPriority(int priority)
    {
        _priority=priority;
    }

    /**
     * @return maximum queue size
     */
    public int getMaxQueued()
    {
        return _maxQueued;
    }

    /**
     * @param max job queue size
     */
    public void setMaxQueued(int max)
    {
        if (isRunning())
            throw new IllegalStateException("started");
        _maxQueued=max;
    }

    /** Get the maximum thread idle time.
     * Delegated to the named or anonymous Pool.
     * @see #setMaxIdleTimeMs
     * @return Max idle time in ms.
     */
    @ManagedAttribute("maximum time a thread may be idle in ms")
    public int getMaxIdleTimeMs()
    {
        return _maxIdleTimeMs;
    }

    /** Set the maximum number of threads.
     * Delegated to the named or anonymous Pool.
     * @see #setMaxThreads
     * @return maximum number of threads.
     */
    @Override
    @ManagedAttribute("maximum number of threads in the pool")
    public int getMaxThreads()
    {
        return _maxThreads;
    }

    /** Get the minimum number of threads.
     * Delegated to the named or anonymous Pool.
     * @see #setMinThreads
     * @return minimum number of threads.
     */
    @Override
    @ManagedAttribute("minimum number of threads in the pool")
    public int getMinThreads()
    {
        return _minThreads;
    }

    /**
     * @return The name of the BoundedThreadPool.
     */
    @ManagedAttribute("name of the thread pool")
    public String getName()
    {
        return _name;
    }

    /** Get the priority of the pool threads.
     *  @return the priority of the pool threads.
     */
    @ManagedAttribute("priority of threads in the pool")
    public int getThreadsPriority()
    {
        return _priority;
    }

    /**
     * Delegated to the named or anonymous Pool.
     */
    @ManagedAttribute("thead pool using a daemon thread")
    public boolean isDaemon()
    {
        return _daemon;
    }

    public boolean isDetailedDump()
    {
        return _detailedDump;
    }

    public void setDetailedDump(boolean detailedDump)
    {
        _detailedDump = detailedDump;
    }

    @Override
    public boolean dispatch(Runnable job)
    {
        LOG.debug("{} dispatched {}",this,job);
        if (isRunning())
        {
            final int jobQ = _jobs.size();
            final int idle = getIdleThreads();
            if(_jobs.offer(job))
            {
                // If we had no idle threads or the jobQ is greater than the idle threads
                if (idle==0 || jobQ>idle)
                {
                    int threads=_threadsStarted.get();
                    if (threads<_maxThreads)
                        startThread(threads);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void execute(Runnable job)
    {
        if (!dispatch(job))
        {
            LOG.warn("{} rejected {}",this,job);
            throw new RejectedExecutionException(job.toString());
        }
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
     * @return The total number of threads currently in the pool
     */
    @Override
    public int getThreads()
    {
        return _threadsStarted.get();
    }

    /**
     * @return The number of idle threads in the pool
     */
    @Override
    public int getIdleThreads()
    {
        return _threadsIdle.get();
    }

    /**
     * @return True if the pool is at maxThreads and there are not more idle threads than queued jobs
     */
    @Override
    public boolean isLowOnThreads()
    {
        return _threadsStarted.get()==_maxThreads && _jobs.size()>=_threadsIdle.get();
    }

    private boolean startThread(int threads)
    {
        final int next=threads+1;
        if (!_threadsStarted.compareAndSet(threads,next))
            return false;

        boolean started=false;
        try
        {
            Thread thread=newThread(_runnable);
            thread.setDaemon(isDaemon());
            thread.setPriority(getThreadsPriority());
            thread.setName(_name+"-"+thread.getId());
            _threads.add(thread);

            thread.start();
            started=true;
        }
        finally
        {
            if (!started)
                _threadsStarted.decrementAndGet();
        }
        return started;
    }

    protected Thread newThread(Runnable runnable)
    {
        return new Thread(runnable);
    }


    @Override
    @ManagedOperation("dump thread state")
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Object> dump = new ArrayList<>(getMaxThreads());
        for (final Thread thread: _threads)
        {
            final StackTraceElement[] trace=thread.getStackTrace();
            boolean inIdleJobPoll=false;
            for (StackTraceElement t : trace)
            {
                if ("idleJobPoll".equals(t.getMethodName()))
                {
                    inIdleJobPoll = true;
                    break;
                }
            }
            final boolean idle=inIdleJobPoll;

            if (isDetailedDump())
            {
                dump.add(new Dumpable()
                {
                    @Override
                    public void dump(Appendable out, String indent) throws IOException
                    {
                        out.append(String.valueOf(thread.getId())).append(' ').append(thread.getName()).append(' ').append(thread.getState().toString()).append(idle?" IDLE":"").append('\n');
                        if (!idle)
                            AggregateLifeCycle.dump(out,indent,Arrays.asList(trace));
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
                dump.add(thread.getId()+" "+thread.getName()+" "+thread.getState()+" @ "+(trace.length>0?trace[0]:"???")+(idle?" IDLE":""));
            }
        }

        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out,indent,dump);
    }

    @Override
    public String toString()
    {
        return String.format("%s{%s,%d<=%d<=%d/%d,%d}",_name,getState(),getMinThreads(),getIdleThreads(),getThreads(),getMaxThreads(),(_jobs==null?-1:_jobs.size()));
    }

    private Runnable idleJobPoll() throws InterruptedException
    {
        return _jobs.poll(_maxIdleTimeMs,TimeUnit.MILLISECONDS);
    }

    private Runnable _runnable = new Runnable()
    {
        @Override
        public void run()
        {
            boolean shrink=false;
            try
            {
                Runnable job=_jobs.poll();
                while (isRunning())
                {
                    // Job loop
                    while (job!=null && isRunning())
                    {
                        runJob(job);
                        job=_jobs.poll();
                    }

                    // Idle loop
                    try
                    {
                        _threadsIdle.incrementAndGet();

                        while (isRunning() && job==null)
                        {
                            if (_maxIdleTimeMs<=0)
                                job=_jobs.take();
                            else
                            {
                                // maybe we should shrink?
                                final int size=_threadsStarted.get();
                                if (size>_minThreads)
                                {
                                    long last=_lastShrink.get();
                                    long now=System.currentTimeMillis();
                                    if (last==0 || (now-last)>_maxIdleTimeMs)
                                    {
                                        shrink=_lastShrink.compareAndSet(last,now) &&
                                        _threadsStarted.compareAndSet(size,size-1);
                                        if (shrink)
                                            return;
                                    }
                                }
                                job=idleJobPoll();
                            }
                        }
                    }
                    finally
                    {
                        _threadsIdle.decrementAndGet();
                    }
                }
            }
            catch(InterruptedException e)
            {
                LOG.ignore(e);
            }
            catch(Throwable e)
            {
                LOG.warn(e);
            }
            finally
            {
                if (!shrink)
                    _threadsStarted.decrementAndGet();
                _threads.remove(Thread.currentThread());
            }
        }
    };

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
     * @param id The thread ID to interrupt.
     * @return true if the thread was found and interrupted.
     */
    @ManagedOperation("interrupt a pool thread")
    public boolean interruptThread(@Name("id") long id)
    {
        for (Thread thread: _threads)
        {
            if (thread.getId()==id)
            {
                thread.interrupt();
                return true;
            }
        }
        return false;
    }

    /**
     * @param id The thread ID to interrupt.
     * @return true if the thread was found and interrupted.
     */
    @ManagedOperation("dump a pool thread stack")
    public String dumpThread(@Name("id") long id)
    {
        for (Thread thread: _threads)
        {
            if (thread.getId()==id)
            {
                StringBuilder buf = new StringBuilder();
                buf.append(thread.getId()).append(" ").append(thread.getName()).append(" ").append(thread.getState()).append(":\n");
                for (StackTraceElement element : thread.getStackTrace())
                    buf.append("  at ").append(element.toString()).append('\n');
                return buf.toString();
            }
        }
        return null;
    }
}
