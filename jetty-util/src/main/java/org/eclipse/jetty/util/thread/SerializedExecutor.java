//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.LoggerFactory;

/**
 * Ensures serial invocation of submitted tasks, respecting the {@link InvocationType}
 * <p>
 * The {@link InvocationType} of the {@link Runnable} returned from this class is
 * the type of the first task offered. If subsequent tasks are offered, then they may need to be executed
 * with {@link Executor#execute(Runnable)} or invoked with {@link Invocable#invokeNonBlocking(Runnable)}
 * depending on their {@link InvocationType} and/or the calling threads type (as per {@link Invocable#isNonBlockingInvocation()}.
 * <p>
 * This class was inspired by the public domain class
 * <a href="https://github.com/jroper/reactive-streams-servlet/blob/master/reactive-streams-servlet/src/main/java/org/reactivestreams/servlet/NonBlockingMutexExecutor.java">NonBlockingMutexExecutor</a>
 * </p>
 */
public class SerializedExecutor implements Executor
{
    private final AtomicReference<Link> _tail = new AtomicReference<>();
    private final Executor _executor;

    public SerializedExecutor()
    {
        this(Runnable::run);
    }

    public SerializedExecutor(Executor executor)
    {
        _executor = executor;
    }

    private Link newLink(Runnable task)
    {
        switch (Invocable.getInvocationType(task))
        {
            case EITHER:
                return new EitherLink(task);
            case BLOCKING:
                return new BlockingLink(task);
            case NON_BLOCKING:
                return new NonBlockingLink(task);
            default:
                throw new IllegalStateException();
        }
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
            return null;
        Link link = newLink(task);
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
     * Arrange for a task to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from {@link #offer(Runnable)}
     * @param task The task to invoke
     */
    @Override
    public void execute(Runnable task)
    {
        Runnable todo = offer(task);
        if (todo != null)
            _executor.execute(todo);
    }

    /**
     * Arrange for tasks to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from {@link #offer(Runnable...)}
     * @param tasks The tasks to invoke
     */
    public void execute(Runnable... tasks)
    {
        Runnable todo = offer(tasks);
        if (todo != null)
            _executor.execute(todo);
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
        try
        {
            if (task instanceof ErrorHandlingTask)
                ((ErrorHandlingTask)task).accept(t);
        }
        catch (Throwable x)
        {
            if (x != t)
                t.addSuppressed(x);
        }
        LoggerFactory.getLogger(task.getClass()).error("Error", t);
    }

    private abstract class Link implements Runnable, Invocable
    {
        private final Runnable _task;
        private final InvocationType _type;
        private final AtomicReference<Link> _next = new AtomicReference<>();

        public Link(Runnable task, InvocationType type)
        {
            _task = task;
            _type = type;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _type;
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
    }

    private class EitherLink extends Link
    {
        public EitherLink(Runnable task)
        {
            super(task, InvocationType.EITHER);
        }

        @Override
        public void run()
        {
            Link link = this;
            while (link != null)
            {
                try
                {
                    switch (link._type)
                    {
                        case EITHER:
                        case NON_BLOCKING:
                            link._task.run();
                            break;
                        case BLOCKING:
                            if (Invocable.isNonBlockingInvocation())
                            {
                                _executor.execute(link);
                                return;
                            }
                            link._task.run();
                            break;
                    }
                }
                catch (Throwable t)
                {
                    onError(link._task, t);
                }

                link = link.next();
            }
        }
    }

    private class BlockingLink extends Link
    {
        public BlockingLink(Runnable task)
        {
            super(task, InvocationType.BLOCKING);
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

    private class NonBlockingLink extends Link
    {
        public NonBlockingLink(Runnable task)
        {
            super(task, InvocationType.NON_BLOCKING);
        }

        @Override
        public void run()
        {
            Link link = this;
            while (link != null)
            {
                try
                {
                    switch (link._type)
                    {
                        case NON_BLOCKING:
                            link._task.run();
                            break;
                        case BLOCKING:
                            _executor.execute(link);
                            return;
                        case EITHER:
                            Invocable.invokeNonBlocking(link._task);
                            break;
                    }
                }
                catch (Throwable t)
                {
                    onError(link._task, t);
                }

                link = link.next();
            }
        }
    }

    /**
     * Error handling task
     * <p>If a submitted task implements this interface, it will be passed
     * any exceptions thrown when running the task.</p>
     */
    public interface ErrorHandlingTask extends Runnable, Consumer<Throwable>
    {}
}
