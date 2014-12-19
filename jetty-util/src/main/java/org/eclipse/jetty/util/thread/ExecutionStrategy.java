//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>An {@link ExecutionStrategy} executes {@link Runnable} tasks produced by a {@link Producer}.
 * The strategy to execute the task may vary depending on the implementation; the task may be
 * run in the calling thread, or in a new thread, etc.</p>
 * <p>The strategy delegates the production of tasks to a {@link Producer}, and continues to
 * execute tasks until the producer continues to produce them.</p>
 */
public interface ExecutionStrategy
{
    /**
     * Initiates (or resumes) the task production and execution.
     */
    public void execute();

    /**
     * <p>A producer of {@link Runnable} tasks to run.</p>
     * <p>The {@link ExecutionStrategy} will repeatedly invoke {@link #produce()} until
     * the producer returns null, indicating that it has nothing more to produce.</p>
     * <p>When no more tasks can be produced, implementations should arrange for the
     * {@link ExecutionStrategy} to be invoked again in case an external event resumes
     * the tasks production.</p>
     */
    public interface Producer
    {
        /**
         * <p>Produces a task to be executed.</p>
         *
         * @return a task to executed or null if there are no more tasks to execute
         */
        Runnable produce();
    }

    /**
     * <p>A strategy where the caller thread iterates over task production, submitting each
     * task to an {@link Executor} for execution.</p>
     */
    public static class ProduceExecuteRun implements ExecutionStrategy
    {
        private final Producer _producer;
        private final Executor _executor;

        public ProduceExecuteRun(Producer producer, Executor executor)
        {
            this._producer = producer;
            this._executor = executor;
        }

        @Override
        public void execute()
        {
            // Iterate until we are complete.
            while (true)
            {
                // Produce a task.
                Runnable task = _producer.produce();

                if (task == null)
                    break;

                // Execute the task.
                _executor.execute(task);
            }
        }
    }
    
    /**
     * <p>A strategy where the caller thread produces a task, then arranges another
     * thread to continue production, and then runs the task.</p>
     * <p>The phrase 'eat what you kill' comes from the hunting ethic that says a person
     * should not kill anything he or she does not plan on eating. It was taken up in its
     * more general sense by lawyers, who used it to mean that an individual’s earnings
     * should be based on how much business that person brings to the firm and the phrase
     * is now quite common throughout the business world.</p>
     * <p>In this case, the phrase is used to mean that a thread should not produce a
     * task that it does not intend to run. By making producers run the task that they
     * have just produced avoids execution delays and avoids parallel slow down by running
     * the task in the same core, with good chances of having a hot CPU cache. It also
     * avoids the creation of a queue of produced tasks that the system does not yet have
     * capacity to consume, which can save memory and exert back pressure on producers.</p>
     */
    public static class ExecuteProduceRun implements ExecutionStrategy, Runnable
    {
        private final AtomicReference<State> _state = new AtomicReference<>(State.IDLE);
        private final Producer _producer;
        private final Executor _executor;

        public ExecuteProduceRun(Producer producer, Executor executor)
        {
            this._producer = producer;
            this._executor = executor;
        }

        @Override
        public void execute()
        {
            while (true)
            {
                State state = _state.get();
                switch (state)
                {
                    case IDLE:
                        if (!_state.compareAndSet(state, State.PENDING))
                            continue;
                        run();
                        return;

                    default:
                        return;
                }
            }
        }

        @Override
        public void run()
        {
            // A new thread has arrived, so clear the PENDING
            // flag and try to set the PRODUCING flag.
            if (!clearPendingTryProducing())
                return;

            while (true)
            {
                // If we got here, then we are the thread that is producing.
                Runnable task = _producer.produce();

                // If no task was produced...
                if (task == null)
                {
                    // ...and we are the thread that sets the IDLE flag,
                    // then production has stopped.
                    tryIdle();
                    return;
                }

                // We have produced, so clear the PRODUCING flag
                // and try to set the PENDING flag.
                if (clearProducingTryPending())
                {
                    // Spawn a new thread to continue production.
                    _executor.execute(this);
                }

                // Run the task.
                task.run();

                // Once we have run the task, we can try producing again.
                if (!tryProducing())
                    return;
            }
        }

        private boolean tryProducing()
        {
            while (true)
            {
                State state = _state.get();
                switch (state)
                {
                    case PENDING:
                        if (!_state.compareAndSet(state, State.PRODUCING_PENDING))
                            continue;
                        return true;

                    default:
                        return false;
                }
            }
        }

        private boolean clearProducingTryPending()
        {
            while (true)
            {
                State state = _state.get();
                switch (state)
                {
                    case PRODUCING:
                        if (!_state.compareAndSet(state, State.PENDING))
                            continue;
                        return true;
                    case PRODUCING_PENDING:
                        if (!_state.compareAndSet(state, State.PENDING))
                            continue;
                        return false;
                    default:
                        throw new IllegalStateException();
                }
            }
        }

        private boolean clearPendingTryProducing()
        {
            while (true)
            {
                State state = _state.get();
                switch (state)
                {
                    case IDLE:
                        return false;

                    case PENDING:
                        if (!_state.compareAndSet(state, State.PRODUCING))
                            continue;
                        return true;

                    case PRODUCING_PENDING:
                        if (!_state.compareAndSet(state, State.PRODUCING))
                            continue;
                        return false;  // Another thread is already producing

                    case PRODUCING:
                        return false; // Another thread is already producing
                }
            }
        }

        private boolean tryIdle()
        {
            while (true)
            {
                State state = _state.get();
                switch (state)
                {
                    case PRODUCING:
                    case PRODUCING_PENDING:
                        if (!_state.compareAndSet(state, State.IDLE))
                            continue;
                        return true;
                    default:
                        return false;
                }
            }
        }

        private enum State
        {
            IDLE, PRODUCING, PENDING, PRODUCING_PENDING
        }
    }
}
