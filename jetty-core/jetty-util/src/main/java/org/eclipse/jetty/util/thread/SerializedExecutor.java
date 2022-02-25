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

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

/**
 * Ensures serial execution of submitted tasks.
 * <p>
 * An Executor that uses an internal {@link SerializedInvoker} to ensure that only one of the submitted tasks is running
 * at any time.
 * </p>
 */
public class SerializedExecutor implements Executor
{
    private final SerializedInvoker _invoker = new SerializedInvoker()
    {
        @Override
        protected void onError(Runnable task, Throwable t)
        {
            SerializedExecutor.this.onError(task, t);
        }
    };
    private final Executor _executor;

    public SerializedExecutor()
    {
        this(Runnable::run);
    }

    public SerializedExecutor(Executor executor)
    {
        _executor = executor;
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

    /**
     * Arrange for a task to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from
     * the internal {@link SerializedInvoker#offer(Runnable)} method.
     * @param task The task to invoke
     */
    @Override
    public void execute(Runnable task)
    {
        Runnable todo = _invoker.offer(task);
        if (todo != null)
            _executor.execute(todo);
    }

    /**
     * Arrange for tasks to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from
     * the internal {@link SerializedInvoker#offer(Runnable)} method.
     * @param tasks The tasks to invoke
     */
    public void execute(Runnable... tasks)
    {
        Runnable todo = _invoker.offer(tasks);
        if (todo != null)
            _executor.execute(todo);
    }

    /**
     * Error handling task
     * <p>If a submitted task implements this interface, it will be passed
     * any exceptions thrown when running the task.</p>
     */
    public interface ErrorHandlingTask extends Runnable, Consumer<Throwable>
    {}
}
