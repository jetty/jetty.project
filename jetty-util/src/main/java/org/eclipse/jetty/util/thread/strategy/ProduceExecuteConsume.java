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

import java.util.concurrent.Executor;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;

/**
 * <p>A strategy where the caller thread iterates over task production, submitting each
 * task to an {@link Executor} for execution.</p>
 */
public class ProduceExecuteConsume extends ExecutingExecutionStrategy implements ExecutionStrategy
{
    private static final Logger LOG = Log.getLogger(ProduceExecuteConsume.class);

    private final Locker _locker = new Locker();
    private final Producer _producer;
    private State _state = State.IDLE;

    public ProduceExecuteConsume(Producer producer, Executor executor)
    {
        this(producer,executor,Invocable.InvocationType.NON_BLOCKING);
    }
    
    public ProduceExecuteConsume(Producer producer, Executor executor, Invocable.InvocationType preferred)
    {
        super(executor,preferred);
        this._producer = producer;
    }

    @Override
    public void produce()
    {
        try (Lock locked = _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                    _state=State.PRODUCE;
                    break;

                case PRODUCE:
                case EXECUTE:
                    _state=State.EXECUTE;
                    return;
            }
        }

        // Produce until we no task is found.
        while (true)
        {
            // Produce a task.
            Runnable task = _producer.produce();
            if (LOG.isDebugEnabled())
                LOG.debug("{} produced {}", _producer, task);

            if (task == null)
            {
                try (Lock locked = _locker.lock())
                {
                    switch(_state)
                    {
                        case IDLE:
                            throw new IllegalStateException();
                        case PRODUCE:
                            _state=State.IDLE;
                            return;
                        case EXECUTE:
                            _state=State.PRODUCE;
                            continue;
                    }
                }
            }

            // Execute the task.
            execute(task);
        }        
    }

    @Override
    public void dispatch()
    {
        produce();
    }

    public static class Factory implements ExecutionStrategy.Factory
    {
        @Override
        public ExecutionStrategy newExecutionStrategy(Producer producer, Executor executor)
        {
            return new ProduceExecuteConsume(producer, executor);
        }
    }

    private enum State
    {
        IDLE, PRODUCE, EXECUTE
    }
}
