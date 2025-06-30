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

import java.util.concurrent.Executor;

import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>A task (typically either a {@link Runnable} or {@link Callable}
 * that declares how it will behave when invoked:</p>
 * <ul>
 * <li>blocking, the invocation will certainly block (e.g. performs blocking I/O)</li>
 * <li>non-blocking, the invocation will certainly <strong>not</strong> block</li>
 * <li>either, the invocation <em>may</em> block</li>
 * </ul>
 *
 * <p>
 * Static methods and are provided that allow the current thread to be tagged
 * with a {@link ThreadLocal} to indicate if it has a blocking invocation type.
 * </p>
 */
public interface Invocable
{
    Runnable NOOP = () -> {};

    ThreadLocal<Boolean> __nonBlocking = new ThreadLocal<>();

    /**
     * <p>The behavior of an {@link Invocable} task when it is called.</p>
     * <p>Typically, tasks such as {@link Runnable}s or {@link org.eclipse.jetty.util.Callback}s declare their
     * invocation type; this information is then used by the code that should
     * invoke the {@code Runnable} or {@code Callback} to decide whether to
     * invoke it directly, or submit it to a thread pool to be invoked by
     * a different thread.</p>
     */
    enum InvocationType
    {
        /**
         * <p>Invoking the task may block the invoker thread,
         * and the invocation may be performed immediately (possibly blocking
         * the invoker thread) or deferred to a later time, for example
         * by submitting the task to a thread pool.</p>
         * <p>This invocation type is suitable for tasks that
         * call application code, for example to process an HTTP request.</p>
         */
        BLOCKING
        {
            public void runWithoutBlocking(Runnable task, Executor executor)
            {
                executor.execute(task);
            }
        },
        /**
         * <p>Invoking the task does not block the invoker thread,
         * and the invocation must be performed immediately in the invoker thread.</p>
         * <p>This invocation type is suitable for tasks that can not be deferred and is
         * guaranteed to never block the invoker thread.</p>
         */
        NON_BLOCKING
        {
            public void runWithoutBlocking(Runnable task, Executor ignored)
            {
                task.run();
            }
        },
        /**
         * <p>Invoking the task may act either as a {@code BLOCKING} task if invoked directly; or as a {@code NON_BLOCKING}
         * task if invoked via {@link Invocable#invokeNonBlocking(Runnable)}. The implementation of the task must check
         * {@link Invocable#isNonBlockingInvocation()} to determine how it was called.
         * </p>
         * <p>This invocation type is suitable for tasks that have multiple subtasks, some of which that cannot be deferred
         * mixed with other subtasks that can be.
         * An invoker which has an {@code EITHER} task must call it immediately, either directly, so that it may block; or
         * via {@link Invocable#invokeNonBlocking(Runnable)} so that it may not.
         * The invoker cannot defer the task execution, and specifically it must not
         * queue the {@code EITHER} task in a thread pool.
         * </p>
         * <p>See the {@link org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy} for an example of
         * both an invoker of {@code EITHER} tasks, and as an implementation of an {@code EITHER} task, when used in a
         * chain of {@link ExecutionStrategy}s.</p>
         */
        EITHER
        {
            public void runWithoutBlocking(Runnable task, Executor ignored)
            {
                Invocable.invokeNonBlocking(task);
            }
        };

        /**
         * Run or Execute the task according to the InvocationType without blocking the caller:
         * <dl>
         *   <dt>{@link InvocationType#NON_BLOCKING}</dt>
         *   <dd>The task is run directly</dd>
         *   <dt>{@link InvocationType#BLOCKING}</dt>
         *   <dd>The task is executed by the passed executor</dd>
         *   <dt>{@link InvocationType#EITHER}</dt>
         *   <dd>The task is invoked via {@link Invocable#invokeNonBlocking(Runnable)}</dd>
         * </dl>
         * @param task The task to run
         * @param executor The executor to use if necessary
         */
        public abstract void runWithoutBlocking(Runnable task, Executor executor);
    }

    /**
     * <p>A task with an {@link InvocationType}.</p>
     */
    interface Task extends Invocable, Runnable
    {
        /**
         * An abstract partial implementation of Task
         */
        abstract class Abstract implements Task
        {
            private final InvocationType type;

            public Abstract(InvocationType type)
            {
                this.type = type;
            }

            @Override
            public InvocationType getInvocationType()
            {
                return type;
            }

            @Override
            public String toString()
            {
                return String.format("%s@%x[%s]", TypeUtil.toShortName(getClass()), hashCode(), getInvocationType());
            }
        }
    }

    // TODO review.  Handy for lambdas that throw (eg LifeCycle#start())
    // TODO: there is already java.util.Callable, can we use it?
    interface Callable extends Invocable
    {
        void call() throws Exception;
    }

    /**
     * <p>A {@link Runnable} decorated with an {@link InvocationType}.</p>
     */
    class ReadyTask extends Task.Abstract
    {
        private final Runnable task;

        public ReadyTask(InvocationType type, Runnable task)
        {
            super(type);
            this.task = task;
        }

        public Runnable getTask()
        {
            return task;
        }

        @Override
        public void run()
        {
            task.run();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s|%s]", getClass().getSimpleName(), hashCode(), getInvocationType(), task);
        }
    }

    /**
     * <p>Creates a {@link Task} from the given InvocationType and Runnable.</p>
     *
     * @param type the InvocationType
     * @param task the Runnable
     * @return a new Task
     */
    static Task from(InvocationType type, Runnable task)
    {
        if (task instanceof Task t && t.getInvocationType() == type)
            return t;
        return new ReadyTask(type, task);
    }

    /**
     * Test if the current thread has been tagged as non blocking
     *
     * @return True if the task the current thread is running has
     * indicated that it will not block.
     */
    static boolean isNonBlockingInvocation()
    {
        return Boolean.TRUE.equals(__nonBlocking.get());
    }

    /**
     * Invoke a task with the calling thread, tagged to indicate
     * that it will not block.
     *
     * @param task The task to invoke.
     */
    static void invokeNonBlocking(Runnable task)
    {
        Boolean wasNonBlocking = __nonBlocking.get();
        try
        {
            __nonBlocking.set(Boolean.TRUE);
            task.run();
        }
        finally
        {
            __nonBlocking.set(wasNonBlocking);
        }
    }

    /**
     * Combine two invocation type.
     * @param it1 A type
     * @param it2 Another type
     * @return The combination of both type, where any tendency to block overrules any non blocking.
     */
    static InvocationType combine(InvocationType it1, InvocationType it2)
    {
        if (it1 != null && it2 != null)
        {
            if (it1 == it2)
                return it1;
            if (it1 == InvocationType.EITHER)
                return it2;
            if (it2 == InvocationType.EITHER)
                return it1;
        }
        return InvocationType.BLOCKING;
    }

    static InvocationType combineTypes(InvocationType... it)
    {
        if (it == null || it.length == 0)
            return InvocationType.BLOCKING;
        InvocationType type = it[0];
        for (int i = 1; i < it.length; i++)
            type = combine(type, it[i]);
        return type;
    }

    /**
     * Get the invocation type of an Object.
     *
     * @param o The object to check the invocation type of.
     * @return If the object is an Invocable, it is coerced and the {@link #getInvocationType()}
     * used, otherwise {@link InvocationType#BLOCKING} is returned.
     */
    static InvocationType getInvocationType(Object o)
    {
        if (o instanceof Invocable)
            return ((Invocable)o).getInvocationType();
        return InvocationType.BLOCKING;
    }

    /**
     * @return The InvocationType of this object
     */
    default InvocationType getInvocationType()
    {
        return InvocationType.BLOCKING;
    }

    /**
     * Combine {@link Runnable}s into a single {@link Runnable} that sequentially calls the others.
     * @param runnables the {@link Runnable}s to combine
     * @return the combined {@link Runnable} with a combined {@link InvocationType}.
     */
    static Runnable combine(Runnable... runnables)
    {
        Runnable result = null;
        for (Runnable runnable : runnables)
        {
            if (runnable == null)
                continue;
            if (result == null)
            {
                result = runnable;
            }
            else
            {
                Runnable first = result;
                result = new Task()
                {
                    @Override
                    public void run()
                    {
                        first.run();
                        runnable.run();
                    }

                    @Override
                    public InvocationType getInvocationType()
                    {
                        return combine(Invocable.getInvocationType(first), Invocable.getInvocationType(runnable));
                    }
                };
            }
        }
        return result;
    }
}
