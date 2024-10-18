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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.Dumpable;
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
    private final String _name;
    private volatile Thread _invokerThread;

    /**
     * Create a new instance whose name is {@code anonymous}.
     */
    public SerializedInvoker()
    {
        this("anonymous");
    }

    /**
     * Create a new instance whose name is derived from the given class.
     * @param nameFrom the class to use as a name.
     */
    public SerializedInvoker(Class<?> nameFrom)
    {
        this(nameFrom.getSimpleName());
    }

    /**
     * Create a new instance with the given name.
     * @param name the name.
     */
    public SerializedInvoker(String name)
    {
        _name = name;
    }

    /**
     * @return whether the current thread is currently executing a task using this invoker
     */
    boolean isCurrentThreadInvoking()
    {
        return _invokerThread == Thread.currentThread();
    }

    /**
     * @throws IllegalStateException when the current thread is not currently executing a task using this invoker
     */
    public void assertCurrentThreadInvoking() throws IllegalStateException
    {
        if (!isCurrentThreadInvoking())
            throw new IllegalStateException();
    }

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
                task = new NamedRunnable(task);
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
        else if (LOG.isDebugEnabled())
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
        else if (LOG.isDebugEnabled())
            LOG.debug("Queued links in {}", this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{name=%s,tail=%s,invoker=%s}", getClass().getSimpleName(), hashCode(), _name, _tail, _invokerThread);
    }

    protected void onError(Runnable task, Throwable t)
    {
        LOG.warn("Serialized invocation error", t);
    }

    private class Link implements Runnable, Invocable, Dumpable
    {
        private final Runnable _task;
        private final AtomicReference<Link> _next = new AtomicReference<>();

        public Link(Runnable task)
        {
            _task = task;
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            if (_task instanceof NamedRunnable nr)
            {
                StringWriter sw = new StringWriter();
                nr.stack.printStackTrace(new PrintWriter(sw));
                Dumpable.dumpObjects(out, indent, nr.toString(), sw.toString());
            }
            else
            {
                Dumpable.dumpObjects(out, indent, _task);
            }
            Link link = _next.get();
            if (link != null)
                link.dump(out, indent);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_task);
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
                _invokerThread = Thread.currentThread();
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
                finally
                {
                    // _invokerThread must be nulled before calling link.next() as
                    // once the latter has executed, another thread can enter Link.run().
                    _invokerThread = null;
                }
                link = link.next();
                if (link == null && LOG.isDebugEnabled())
                    LOG.debug("Next link is null, execution is over in {}", SerializedInvoker.this);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s -> %s}", getClass().getSimpleName(), hashCode(), _task, _next);
        }
    }

    private class NamedRunnable implements Runnable, Invocable
    {
        private static final Logger LOG = LoggerFactory.getLogger(NamedRunnable.class);

        private final Runnable delegate;
        private final String name;
        private final Throwable stack;

        private NamedRunnable(Runnable delegate)
        {
            this.delegate = delegate;
            this.stack = new Throwable();
            this.name = deriveTaskName(delegate, stack);
        }

        private String deriveTaskName(Runnable task, Throwable stack)
        {
            StackTraceElement[] stackTrace = stack.getStackTrace();
            for (StackTraceElement stackTraceElement : stackTrace)
            {
                String className = stackTraceElement.getClassName();
                if (!className.equals(SerializedInvoker.class.getName()) &&
                    !className.equals(SerializedInvoker.this.getClass().getName()) &&
                    !className.equals(getClass().getName()))
                    return "Queued by " + Thread.currentThread().getName() + " at " + stackTraceElement;
            }
            return task.toString();
        }

        @Override
        public void run()
        {
            delegate.run();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(delegate);
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
