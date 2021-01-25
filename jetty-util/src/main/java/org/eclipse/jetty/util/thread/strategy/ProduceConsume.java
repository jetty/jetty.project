//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.util.thread.Locker;

/**
 * <p>A strategy where the caller thread iterates over task production, submitting each
 * task to an {@link Executor} for execution.</p>
 */
public class ProduceConsume implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = Log.getLogger(ExecuteProduceConsume.class);

    private final Locker _locker = new Locker();
    private final Producer _producer;
    private final Executor _executor;
    private State _state = State.IDLE;

    public ProduceConsume(Producer producer, Executor executor)
    {
        this._producer = producer;
        this._executor = executor;
    }

    @Override
    public void produce()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case IDLE:
                    _state = State.PRODUCE;
                    break;

                case PRODUCE:
                case EXECUTE:
                    _state = State.EXECUTE;
                    return;
            }
        }

        // Iterate until we are complete.
        while (true)
        {
            // Produce a task.
            Runnable task = _producer.produce();
            if (LOG.isDebugEnabled())
                LOG.debug("{} produced {}", _producer, task);

            if (task == null)
            {
                try (Locker.Lock lock = _locker.lock())
                {
                    switch (_state)
                    {
                        case IDLE:
                            throw new IllegalStateException();
                        case PRODUCE:
                            _state = State.IDLE;
                            return;
                        case EXECUTE:
                            _state = State.PRODUCE;
                            continue;
                    }
                }
            }

            // Run the task.
            task.run();
        }
    }

    @Override
    public void dispatch()
    {
        _executor.execute(this);
    }

    @Override
    public void run()
    {
        produce();
    }

    private enum State
    {
        IDLE, PRODUCE, EXECUTE
    }
}
