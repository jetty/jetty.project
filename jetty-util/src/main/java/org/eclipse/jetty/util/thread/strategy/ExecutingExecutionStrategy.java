//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread.strategy;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;

/**
 * <p>Base class for strategies that need to execute a task by submitting it to an {@link Executor}.</p>
 * <p>If the submission to the {@code Executor} is rejected (via a {@link RejectedExecutionException}),
 * the task is tested whether it implements {@link Closeable}; if it does, then {@link Closeable#close()}
 * is called on the task object.</p>
 */
public abstract class ExecutingExecutionStrategy implements ExecutionStrategy
{
    private static final Logger LOG = Log.getLogger(ExecutingExecutionStrategy.class);

    private final Executor _executor;

    protected ExecutingExecutionStrategy(Executor executor)
    {
        _executor=executor;
    }

    protected boolean execute(Runnable task)
    {
        try
        {
            _executor.execute(task);
            return true;
        }
        catch(RejectedExecutionException e)
        {
            // If we cannot execute, then close the task and keep producing.
            LOG.debug(e);
            LOG.warn("Rejected execution of {}",task);
            try
            {
                if (task instanceof Closeable)
                    ((Closeable)task).close();
            }
            catch (Exception x)
            {
                e.addSuppressed(x);
                LOG.warn(e);
            }
        }
        return false;
    }
}
