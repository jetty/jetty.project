//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * Implementation of {@link Scheduler} based on JDK's {@link ScheduledThreadPoolExecutor}.
 * <p>
 * While use of {@link ScheduledThreadPoolExecutor} creates futures that will not be used,
 * it has the advantage of allowing to set a property to remove cancelled tasks from its
 * queue even if the task did not fire, which provides a huge benefit in the performance
 * of garbage collection in young generation.
 */
public class ScheduledExecutorScheduler extends AbstractLifeCycle implements Scheduler, Dumpable
{
    private final String name;
    private final boolean daemon;
    private final ClassLoader classloader;
    private final ThreadGroup threadGroup;
    private volatile ScheduledThreadPoolExecutor scheduler;
    private volatile Thread thread;

    public ScheduledExecutorScheduler()
    {
        this(null, false);
    }  

    public ScheduledExecutorScheduler(String name, boolean daemon)
    {
        this (name,daemon, Thread.currentThread().getContextClassLoader());
    }
    
    public ScheduledExecutorScheduler(String name, boolean daemon, ClassLoader threadFactoryClassLoader)
    {
        this(name, daemon, threadFactoryClassLoader, null);
    }

    public ScheduledExecutorScheduler(String name, boolean daemon, ClassLoader threadFactoryClassLoader, ThreadGroup threadGroup)
    {
        this.name = name == null ? "Scheduler-" + hashCode() : name;
        this.daemon = daemon;
        this.classloader = threadFactoryClassLoader == null ? Thread.currentThread().getContextClassLoader() : threadFactoryClassLoader;
        this.threadGroup = threadGroup;
    }

    @Override
    protected void doStart() throws Exception
    {
        scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable r)
            {
                Thread thread = ScheduledExecutorScheduler.this.thread = new Thread(threadGroup, r, name);
                thread.setDaemon(daemon);
                thread.setContextClassLoader(classloader);
                return thread;
            }
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
        // TODO should this just throw ISE?
        if (s==null)
            return new Task(){
                @Override
                public boolean cancel()
                {
                    return false;
                }};

        ScheduledFuture<?> result = s.schedule(task, delay, unit);
        return new ScheduledFutureTask(result);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this);
        Thread thread = this.thread;
        if (thread != null)
        {
            List<StackTraceElement> frames = Arrays.asList(thread.getStackTrace());
            ContainerLifeCycle.dump(out, indent, frames);
        }
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
}
