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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;

/**
 * A {@link org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool} wrapper around {@link ThreadPoolExecutor}.
 */
@ManagedObject("A thread pool")
public class ExecutorThreadPool extends ContainerLifeCycle implements ThreadPool.SizedThreadPool, TryExecutor, VirtualThreads.Configurable
{
    private final ThreadPoolExecutor _executor;
    private final ThreadPoolBudget _budget;
    private final ThreadGroup _group;
    private String _name = "etp" + hashCode();
    private int _minThreads;
    private int _reservedThreads = -1;
    private TryExecutor _tryExecutor = TryExecutor.NO_TRY;
    private int _priority = Thread.NORM_PRIORITY;
    private boolean _daemon;
    private boolean _detailedDump;
    private boolean _useVirtualThreads;

    public ExecutorThreadPool()
    {
        this(200, 8);
    }

    public ExecutorThreadPool(int maxThreads)
    {
        this(maxThreads, Math.min(8, maxThreads));
    }

    public ExecutorThreadPool(int maxThreads, int minThreads)
    {
        this(maxThreads, minThreads, new LinkedBlockingQueue<>());
    }

    public ExecutorThreadPool(int maxThreads, int minThreads, BlockingQueue<Runnable> queue)
    {
        this(new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS, queue), minThreads, -1, null);
    }

    public ExecutorThreadPool(ThreadPoolExecutor executor)
    {
        this(executor, -1);
    }

    public ExecutorThreadPool(ThreadPoolExecutor executor, int reservedThreads)
    {
        this(executor, reservedThreads, null);
    }

    public ExecutorThreadPool(ThreadPoolExecutor executor, int reservedThreads, ThreadGroup group)
    {
        this(executor, Math.min(ProcessorUtils.availableProcessors(), executor.getCorePoolSize()), reservedThreads, group);
    }

    private ExecutorThreadPool(ThreadPoolExecutor executor, int minThreads, int reservedThreads, ThreadGroup group)
    {
        int maxThreads = executor.getMaximumPoolSize();
        if (maxThreads < minThreads)
        {
            executor.shutdownNow();
            throw new IllegalArgumentException("max threads (" + maxThreads + ") cannot be less than min threads (" + minThreads + ")");
        }
        _executor = executor;
        _executor.setThreadFactory(this::newThread);
        _group = group;
        _minThreads = minThreads;
        _reservedThreads = reservedThreads;
        _budget = new ThreadPoolBudget(this);
    }

    /**
     * @return the name of the this thread pool
     */
    @ManagedAttribute("name of this thread pool")
    public String getName()
    {
        return _name;
    }

    /**
     * @param name the name of this thread pool, used to name threads
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _name = name;
    }

    @Override
    @ManagedAttribute("minimum number of threads in the pool")
    public int getMinThreads()
    {
        return _minThreads;
    }

    @Override
    public void setMinThreads(int threads)
    {
        _minThreads = threads;
    }

    @Override
    @ManagedAttribute("maximum number of threads in the pool")
    public int getMaxThreads()
    {
        return _executor.getMaximumPoolSize();
    }

    @Override
    public void setMaxThreads(int threads)
    {
        if (_budget != null)
            _budget.check(threads);
        _executor.setCorePoolSize(threads);
        _executor.setMaximumPoolSize(threads);
    }

    /**
     * @return the maximum thread idle time in ms.
     * @see #setIdleTimeout(int)
     */
    @ManagedAttribute("maximum time a thread may be idle in ms")
    public int getIdleTimeout()
    {
        return (int)_executor.getKeepAliveTime(TimeUnit.MILLISECONDS);
    }

    /**
     * <p>Sets the maximum thread idle time in ms.</p>
     * <p>Threads that are idle for longer than this
     * period may be stopped.</p>
     *
     * @param idleTimeout the maximum thread idle time in ms.
     * @see #getIdleTimeout()
     */
    public void setIdleTimeout(int idleTimeout)
    {
        _executor.setKeepAliveTime(idleTimeout, TimeUnit.MILLISECONDS);
    }

    /**
     * @return number of reserved threads or -1 to indicate that the number is heuristically determined
     * @see #setReservedThreads(int)
     */
    @ManagedAttribute("the number of reserved threads in the pool")
    public int getReservedThreads()
    {
        if (isStarted())
            return getBean(ReservedThreadExecutor.class).getCapacity();
        return _reservedThreads;
    }

    /**
     * Sets the number of reserved threads.
     *
     * @param reservedThreads number of reserved threads or -1 to determine the number heuristically
     * @see #getReservedThreads()
     */
    public void setReservedThreads(int reservedThreads)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _reservedThreads = reservedThreads;
    }

    public void setThreadsPriority(int priority)
    {
        _priority = priority;
    }

    public int getThreadsPriority()
    {
        return _priority;
    }

    /**
     * @return whether this thread pool uses daemon threads
     * @see #setDaemon(boolean)
     */
    @ManagedAttribute("whether this thread pool uses daemon threads")
    public boolean isDaemon()
    {
        return _daemon;
    }

    /**
     * @param daemon whether this thread pool uses daemon threads
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

    @Override
    @ManagedAttribute("number of threads in the pool")
    public int getThreads()
    {
        return _executor.getPoolSize();
    }

    @Override
    @ManagedAttribute("number of idle threads in the pool")
    public int getIdleThreads()
    {
        return _executor.getPoolSize() - _executor.getActiveCount();
    }

    @Override
    public void execute(Runnable command)
    {
        _executor.execute(command);
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        TryExecutor tryExecutor = _tryExecutor;
        return tryExecutor != null && tryExecutor.tryExecute(task);
    }

    @Override
    @ManagedAttribute(value = "thread pool is low on threads", readonly = true)
    public boolean isLowOnThreads()
    {
        return getThreads() == getMaxThreads() && _executor.getQueue().size() >= getIdleThreads();
    }

    @Override
    public boolean isUseVirtualThreads()
    {
        return _useVirtualThreads;
    }

    @Override
    public void setUseVirtualThreads(boolean useVirtualThreads)
    {
        try
        {
            VirtualThreads.Configurable.super.setUseVirtualThreads(useVirtualThreads);
            _useVirtualThreads = useVirtualThreads;
        }
        catch (UnsupportedOperationException ignored)
        {
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_executor.isShutdown())
            throw new IllegalStateException("This thread pool is not restartable");
        for (int i = 0; i < _minThreads; ++i)
        {
            _executor.prestartCoreThread();
        }

        _tryExecutor = new ReservedThreadExecutor(this, _reservedThreads);
        addBean(_tryExecutor);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_tryExecutor);
        _tryExecutor = TryExecutor.NO_TRY;
        _executor.shutdownNow();
        _budget.reset();
    }

    @Override
    public void join() throws InterruptedException
    {
        _executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    @Override
    public ThreadPoolBudget getThreadPoolBudget()
    {
        return _budget;
    }

    protected Thread newThread(Runnable job)
    {
        Thread thread = new Thread(_group, job);
        thread.setDaemon(isDaemon());
        thread.setPriority(getThreadsPriority());
        thread.setName(getName() + "-" + thread.getId());
        return thread;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        String prefix = getName() + "-";
        List<Dumpable> threads = Thread.getAllStackTraces().entrySet().stream()
            .filter(entry -> entry.getKey().getName().startsWith(prefix))
            .map(entry ->
            {
                Thread thread = entry.getKey();
                StackTraceElement[] frames = entry.getValue();
                String knownMethod = null;
                for (StackTraceElement frame : frames)
                {
                    if ("getTask".equals(frame.getMethodName()) && frame.getClassName().endsWith("ThreadPoolExecutor"))
                    {
                        knownMethod = "IDLE ";
                        break;
                    }
                    else if ("reservedWait".equals(frame.getMethodName()) && frame.getClassName().endsWith("ReservedThread"))
                    {
                        knownMethod = "RESERVED ";
                        break;
                    }
                    else if ("select".equals(frame.getMethodName()) && frame.getClassName().endsWith("SelectorProducer"))
                    {
                        knownMethod = "SELECTING ";
                        break;
                    }
                    else if ("accept".equals(frame.getMethodName()) && frame.getClassName().contains("ServerConnector"))
                    {
                        knownMethod = "ACCEPTING ";
                        break;
                    }
                }
                String known = knownMethod == null ? "" : knownMethod;
                return new Dumpable()
                {
                    @Override
                    public void dump(Appendable out, String indent) throws IOException
                    {
                        StringBuilder b = new StringBuilder();
                        b.append(String.valueOf(thread.getId()))
                            .append(" ")
                            .append(thread.getName())
                            .append(" p=").append(String.valueOf(thread.getPriority()))
                            .append(" ")
                            .append(known)
                            .append(thread.getState().toString());

                        if (isDetailedDump())
                        {
                            if (known.isEmpty())
                                Dumpable.dumpObjects(out, indent, b.toString(), (Object[])frames);
                            else
                                Dumpable.dumpObject(out, b.toString());
                        }
                        else
                        {
                            b.append(" @ ").append(frames.length > 0 ? String.valueOf(frames[0]) : "<no_stack_frames>");
                            Dumpable.dumpObject(out, b.toString());
                        }
                    }

                    @Override
                    public String dump()
                    {
                        return null;
                    }
                };
            })
            .collect(Collectors.toList());

        List<Runnable> jobs = Collections.emptyList();
        if (isDetailedDump())
            jobs = new ArrayList<>(_executor.getQueue());
        dumpObjects(out, indent, threads, new DumpableCollection("jobs", jobs));
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%x{%s,%d<=%d<=%d,i=%d,q=%d,%s}",
            getClass().getSimpleName(),
            getName(),
            hashCode(),
            getState(),
            getMinThreads(),
            getThreads(),
            getMaxThreads(),
            getIdleThreads(),
            _executor.getQueue().size(),
            _tryExecutor);
    }
}
