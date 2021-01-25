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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * Implementation of {@link Scheduler} based on JDK's {@link ScheduledThreadPoolExecutor}.
 * <p>
 * While use of {@link ScheduledThreadPoolExecutor} creates futures that will not be used,
 * it has the advantage of allowing to set a property to remove cancelled tasks from its
 * queue even if the task did not fire, which provides a huge benefit in the performance
 * of garbage collection in young generation.
 */
@ManagedObject
public class ScheduledExecutorScheduler extends AbstractLifeCycle implements Scheduler, Dumpable
{
    private final String name;
    private final boolean daemon;
    private final ClassLoader classloader;
    private final ThreadGroup threadGroup;
    private final int threads;
    private final AtomicInteger count = new AtomicInteger();
    private volatile ScheduledThreadPoolExecutor scheduler;
    private volatile Thread thread;

    public ScheduledExecutorScheduler()
    {
        this(null, false);
    }

    public ScheduledExecutorScheduler(String name, boolean daemon)
    {
        this(name, daemon, null);
    }

    public ScheduledExecutorScheduler(@Name("name") String name, @Name("daemon") boolean daemon, @Name("threads") int threads)
    {
        this(name, daemon, null, null, threads);
    }

    public ScheduledExecutorScheduler(String name, boolean daemon, ClassLoader classLoader)
    {
        this(name, daemon, classLoader, null);
    }

    public ScheduledExecutorScheduler(String name, boolean daemon, ClassLoader classLoader, ThreadGroup threadGroup)
    {
        this(name, daemon, classLoader, threadGroup, -1);
    }

    /**
     * @param name The name of the scheduler threads or null for automatic name
     * @param daemon True if scheduler threads should be daemon
     * @param classLoader The classloader to run the threads with or null to use the current thread context classloader
     * @param threadGroup The threadgroup to use or null for no thread group
     * @param threads The number of threads to pass to the the core {@link ScheduledThreadPoolExecutor} or -1 for a
     * heuristic determined number of threads.
     */
    public ScheduledExecutorScheduler(@Name("name") String name, @Name("daemon") boolean daemon, @Name("classLoader") ClassLoader classLoader, @Name("threadGroup") ThreadGroup threadGroup, @Name("threads") int threads)
    {
        this.name = StringUtil.isBlank(name) ? "Scheduler-" + hashCode() : name;
        this.daemon = daemon;
        this.classloader = classLoader == null ? Thread.currentThread().getContextClassLoader() : classLoader;
        this.threadGroup = threadGroup;
        this.threads = threads;
    }

    @Override
    protected void doStart() throws Exception
    {
        int size = threads > 0 ? threads : 1;
        scheduler = new ScheduledThreadPoolExecutor(size, r ->
        {
            Thread thread = ScheduledExecutorScheduler.this.thread = new Thread(threadGroup, r, name + "-" + count.incrementAndGet());
            thread.setDaemon(daemon);
            thread.setContextClassLoader(classloader);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        scheduler.shutdownNow();
        super.doStop();
        scheduler = null;
    }

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit unit)
    {
        ScheduledThreadPoolExecutor s = scheduler;
        if (s == null)
            return () -> false;
        ScheduledFuture<?> result = s.schedule(task, delay, unit);
        return new ScheduledFutureTask(result);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Thread thread = this.thread;
        if (thread == null)
            Dumpable.dumpObject(out, this);
        else
            Dumpable.dumpObjects(out, indent, this, (Object[])thread.getStackTrace());
    }

    private static class ScheduledFutureTask implements Task
    {
        private final ScheduledFuture<?> scheduledFuture;

        ScheduledFutureTask(ScheduledFuture<?> scheduledFuture)
        {
            this.scheduledFuture = scheduledFuture;
        }

        @Override
        public boolean cancel()
        {
            return scheduledFuture.cancel(false);
        }
    }

    @ManagedAttribute("The name of the scheduler")
    public String getName()
    {
        return name;
    }

    @ManagedAttribute("Whether the scheduler uses daemon threads")
    public boolean isDaemon()
    {
        return daemon;
    }

    @ManagedAttribute("The number of scheduler threads")
    public int getThreads()
    {
        return threads;
    }
}
