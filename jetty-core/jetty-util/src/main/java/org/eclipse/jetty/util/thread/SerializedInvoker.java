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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures serial invocation of submitted tasks.
 * <p>
 * The {@link InvocationType} of the {@link Runnable} returned from this class is
 * always {@link InvocationType#BLOCKING} since a blocking task may be added to the queue
 * at any time.
 * <p>
 * This class was inspired by the public domain class
 * <a href="https://github.com/jroper/reactive-streams-servlet/blob/master/reactive-streams-servlet/src/main/java/org/reactivestreams/servlet/NonBlockingMutexExecutor.java">NonBlockingMutexExecutor</a>
 * </p>
 */
public class SerializedInvoker
{
    private static final Logger LOG = LoggerFactory.getLogger(SerializedInvoker.class);

    private final AtomicReference<Link> _tail = new AtomicReference<>();

    /**
     * Arrange for a task to be invoked, mutually excluded from other tasks.
     * @param task The task to invoke
     * @return A Runnable that must be called to invoke the passed task and possibly other tasks. Null if the
     *         task will be invoked by another caller.
     */
    public Runnable offer(Runnable task)
    {
        if (task == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Offering task null, skipping it in {}", this);
            return null;
        }
        // The NamedRunnable logger is checked to make it possible to enable the nice task names in a debugger
        // while not flooding the logs nor stderr with logging statements just by adding
        // org.eclipse.jetty.util.thread.SerializedInvoker$NamedRunnable.LEVEL=DEBUG to jetty-logging.properties.
        if (NamedRunnable.LOG.isDebugEnabled())
        {
            if (!(task instanceof NamedRunnable))
            {
                // Wrap the given task with another one that's going to delegate run() to the wrapped task while the
                // wrapper's toString() returns a description of the place in code where SerializedInvoker.run() was called.
                task = new NamedRunnable(task, deriveTaskName(task));
            }
        }
        Link link = new Link(task);
        if (LOG.isDebugEnabled())
            LOG.debug("Offering link {} of {}", link, this);
        Link penultimate = _tail.getAndSet(link);
        if (penultimate == null)
            return link;
        penultimate._next.lazySet(link);
        return null;
    }

    protected String deriveTaskName(Runnable task)
    {
        StackTraceElement[] stackTrace = new Exception().getStackTrace();
        for (StackTraceElement stackTraceElement : stackTrace)
        {
            String className = stackTraceElement.getClassName();
            if (!className.equals(SerializedInvoker.class.getName()) && !className.equals(getClass().getName()))
                return "Queued at " + stackTraceElement;
        }
        return task.toString();
    }

    /**
     * Arrange for tasks to be invoked, mutually excluded from other tasks.
     * @param tasks The tasks to invoke
     * @return A Runnable that must be called to invoke the passed tasks and possibly other tasks. Null if the
     *         tasks will be invoked by another caller.
     */
    public Runnable offer(Runnable... tasks)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Offering {} tasks in {}", tasks.length, this);
        Runnable runnable = null;
        for (Runnable task : tasks)
        {
            if (runnable == null)
                runnable = offer(task);
            else
                offer(task);
        }
        return runnable;
    }

    /**
     * Arrange for a task to be run, mutually excluded from other tasks.
     * This is equivalent to directly running any {@link Runnable} returned from {@link #offer(Runnable)}
     * @param task The task to invoke
     */
    public void run(Runnable task)
    {
        Runnable todo = offer(task);
        if (todo != null)
            todo.run();
        else
            if (LOG.isDebugEnabled())
                LOG.debug("Queued link in {}", this);
    }

    /**
     * Arrange for tasks to be executed, mutually excluded from other tasks.
     * This is equivalent to directly running any {@link Runnable} returned from {@link #offer(Runnable...)}
     * @param tasks The tasks to invoke
     */
    public void run(Runnable... tasks)
    {
        Runnable todo = offer(tasks);
        if (todo != null)
            todo.run();
        else
            if (LOG.isDebugEnabled())
                LOG.debug("Queued links in {}", this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{tail=%s}", getClass().getSimpleName(), hashCode(), _tail);
    }

    protected void onError(Runnable task, Throwable t)
    {
        LOG.warn("Serialized invocation error", t);
    }

    private class Link implements Runnable, Invocable
    {
        private final Runnable _task;
        private final AtomicReference<Link> _next = new AtomicReference<>();

        public Link(Runnable task)
        {
            _task = task;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.BLOCKING;
        }

        Link next()
        {
            // Are we the current the last Link?
            if (_tail.compareAndSet(this, null))
                return null;

            // not the last task, so its next link will eventually be set
            while (true)
            {
                Link next = _next.get();
                if (next != null)
                    return next;
                Thread.onSpinWait();
            }
        }

        @Override
        public void run()
        {
            Link link = this;
            while (link != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Running link {} of {}", link, SerializedInvoker.this);
                try
                {
                    link._task.run();
                }
                catch (Throwable t)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failed while running link {} of {}", link, SerializedInvoker.this, t);
                    onError(link._task, t);
                }
                link = link.next();
                if (link == null && LOG.isDebugEnabled())
                    LOG.debug("Next link is null, execution is over in {}", SerializedInvoker.this);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s] -> %s", getClass().getSimpleName(), hashCode(), _task, _next);
        }
    }

    private record NamedRunnable(Runnable delegate, String name) implements Runnable
    {
        private static final Logger LOG = LoggerFactory.getLogger(NamedRunnable.class);

        @Override
        public void run()
        {
            delegate.run();
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
