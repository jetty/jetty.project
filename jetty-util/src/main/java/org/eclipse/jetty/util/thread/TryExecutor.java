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
import java.util.concurrent.RejectedExecutionException;

/**
 * A variation of Executor that can confirm if a thread is available immediately
 */
public interface TryExecutor extends Executor
{
    /**
     * Attempt to execute a task.
     *
     * @param task The task to be executed
     * @return True IFF the task has been given directly to a thread to execute.  The task cannot be queued pending the later availability of a Thread.
     */
    boolean tryExecute(Runnable task);

    @Override
    default void execute(Runnable task)
    {
        if (!tryExecute(task))
            throw new RejectedExecutionException();
    }

    public static TryExecutor asTryExecutor(Executor executor)
    {
        if (executor instanceof TryExecutor)
            return (TryExecutor)executor;
        return new NoTryExecutor(executor);
    }

    public static class NoTryExecutor implements TryExecutor
    {
        private final Executor executor;

        public NoTryExecutor(Executor executor)
        {
            this.executor = executor;
        }

        @Override
        public void execute(Runnable task)
        {
            executor.execute(task);
        }

        @Override
        public boolean tryExecute(Runnable task)
        {
            return false;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), executor);
        }
    }

    TryExecutor NO_TRY = new TryExecutor()
    {
        @Override
        public boolean tryExecute(Runnable task)
        {
            return false;
        }

        @Override
        public String toString()
        {
            return "NO_TRY";
        }
    };
}
