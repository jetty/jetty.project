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
 * Strategies to execute Producers
 */
public abstract class ExecutionStrategy
{
    public interface Producer
    {
        /**
         * Produce a task to run
         *
         * @return A task to run or null if we are complete.
         */
        Runnable produce();

        /**
         * Called to signal production is completed
         */
        void onProductionComplete();
    }

    protected final Producer _producer;
    protected final Executor _executor;

    protected ExecutionStrategy(Producer producer, Executor executor)
    {
        _producer = producer;
        _executor = executor;
    }

    public abstract void produce();
    
    /**
     * Simple iterative strategy.
     * Iterate over production until complete and execute each task.
     */
    public static class Iterative extends ExecutionStrategy
    {
        public Iterative(Producer producer, Executor executor)
        {
            super(producer, executor);
        }

        public void produce()
        {
            try
            {
                // Iterate until we are complete
                while (true)
                {
                    // produce a task
                    Runnable task = _producer.produce();

                    if (task == null)
                        break;

                    // execute the task
                    _executor.execute(task);
                }
            }
            finally
            {
                _producer.onProductionComplete();
            }
        }
    }
    
    /**
     * A Strategy that allows threads to run the tasks that they have produced,
     * so execution is done with a hot cache (ie threads eat what they kill).
     * <p/>
     * The phrase 'eat what you kill' comes from the hunting ethic that says a person
     * shouldn’t kill anything he or she doesn’t plan on eating. It was taken up in its
     * more general sense by lawyers, who used it to mean that an individual’s earnings
     * should be based on how much business that person brings to the firm and the phrase
     * is now quite common throughout the business world.  In this case, the phrase is
     * used to mean that a thread should not produce a task that it does not intend
     * to consume.  By making producers consume the task that they have just generated
     * avoids execution delays and avoids parallel slow down by doing the consumption with
     * a hot cache.  It also avoids the creation of a queue of produced events that the
     * system does not yet have capacity to consume, which can save memory and exert back
     * pressure on producers.
     */
    public static class EatWhatYouKill extends ExecutionStrategy implements Runnable
    {
        private final AtomicReference<State> _state = new AtomicReference<>(State.IDLE);

        public EatWhatYouKill(Producer producer, Executor executor)
        {
            super(producer, executor);
        }

        public void produce()
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
            // A new thread has arrived, so clear pending
            // and try to set producing.
            if (!clearPendingTryProducing())
                return;

            while (true)
            {
                // If we got here, then we are the thread that is producing
                Runnable task = _producer.produce();

                // If no task was produced
                if (task == null)
                {
                    // If we are the thread that sets idle
                    if (tryIdle())
                        // signal that production has stopped
                        _producer.onProductionComplete();
                    return;
                }

                // We have finished producing, so clear producing and try to
                // set pending
                if (clearProducingTryPending())
                    _executor.execute(this);

                // consume the task
                task.run();

                // Once we have consumed, we can try producing again
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
