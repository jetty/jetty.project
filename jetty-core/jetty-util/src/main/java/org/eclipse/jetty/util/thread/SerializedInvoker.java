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
            return null;
        Link link = new Link(task);
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
                try
                {
                    link._task.run();
                }
                catch (Throwable t)
                {
                    onError(link._task, t);
                }
                link = link.next();
            }
        }
    }
}
