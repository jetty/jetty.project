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

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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

    public static class Factory
    {
        private static final Logger LOG = Log.getLogger(Factory.class);

        public static ExecutionStrategy instanceFor(Producer producer, Executor executor)
        {
            // TODO remove this mechanism before release
            String strategy = System.getProperty(producer.getClass().getName()+".ExecutionStrategy");
            if (strategy!=null)
            {
                try
                {
                    Class<? extends ExecutionStrategy> c = Loader.loadClass(producer.getClass(),strategy);
                    Constructor<? extends ExecutionStrategy> m = c.getConstructor(Producer.class,Executor.class);
                    LOG.info("Use {} for {}",c.getSimpleName(),producer.getClass().getName());
                    return  m.newInstance(producer,executor);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
            
            return new ExecuteProduceRun(producer,executor);
        }
    }
    
    /**
     * <p>A strategy where the caller thread iterates over task production, submitting each
     * task to an {@link Executor} for execution.</p>
     */
    public static class ProduceRun implements ExecutionStrategy
    {
        private final Producer _producer;

        public ProduceRun(Producer producer)
        {
            this._producer = producer;
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

                // run the task.
                task.run();
            }
        }
    }
    
    /**
     * <p>A strategy where the caller thread iterates over task production, submitting each
     * task to an {@link Executor} for execution.</p>
     */
    public static class ProduceExecuteRun implements ExecutionStrategy
    {
        private static final Logger LOG = Log.getLogger(ExecutionStrategy.class);
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
                if (LOG.isDebugEnabled())
                    LOG.debug("{} PER produced {}",_producer,task);

                if (task == null)
                    break;

                // Execute the task.
                _executor.execute(task);
            }
        }
    }
    
    /**
     * <p>A strategy where the thread calls produce will always run the resulting task
     * itself.  The strategy may dispatches another thread to continue production.
     * </p>
     * <p>The strategy is also known by the nickname 'eat what you kill', which comes from 
     * the hunting ethic that says a person should not kill anything he or she does not 
     * plan on eating. In this case, the phrase is used to mean that a thread should 
     * not produce a task that it does not intend to run. By making producers run the 
     * task that they have just produced avoids execution delays and avoids parallel slow 
     * down by running the task in the same core, with good chances of having a hot CPU 
     * cache. It also avoids the creation of a queue of produced tasks that the system 
     * does not yet have capacity to consume, which can save memory and exert back 
     * pressure on producers.
     * </p>
     */
    public static class ExecuteProduceRun implements ExecutionStrategy, Runnable
    {
        private static final Logger LOG = Log.getLogger(ExecutionStrategy.class);
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
            if (LOG.isDebugEnabled())
                LOG.debug("{} EPR executed",_producer);
            // A new thread has arrived, so clear the PENDING
            // flag and try to set the PRODUCING flag.
            if (!clearPendingTryProducing())
                return;

            while (true)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} EPR producing",_producer);
                
                // If we got here, then we are the thread that is producing.
                Runnable task = _producer.produce();
                if (LOG.isDebugEnabled())
                    LOG.debug("{} EPR produced {}",_producer,task);

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
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} EPR executed self",_producer);
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
