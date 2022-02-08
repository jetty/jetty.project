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

package org.eclipse.jetty.util.thread.strategy;

import java.util.concurrent.Executor;

import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A strategy where the caller thread iterates over task production, submitting each
 * task to an {@link Executor} for execution.</p>
 */
public class ProduceConsume implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(ExecuteProduceConsume.class);

    private final AutoLock _lock = new AutoLock();
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
        try (AutoLock lock = _lock.lock())
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
                default:
                    throw new IllegalStateException(_state.toString());
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
                try (AutoLock lock = _lock.lock())
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
                        default:
                            throw new IllegalStateException(_state.toString());
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
