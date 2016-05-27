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

package org.eclipse.jetty.util.thread;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume;

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
     * <p>Initiates (or resumes) the task production and consumption.</p>
     * <p>This method guarantees that the task is never run by the
     * thread that called this method.</p>
     *
     * TODO review the need for this (only used by HTTP2 push)
     * @see #produce()
     */
    public void dispatch();

    /**
     * <p>Initiates (or resumes) the task production and consumption.</p>
     * <p>The produced task may be run by the same thread that called
     * this method.</p>
     *
     * @see #dispatch()
     */
    public void produce();
    
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
     * <p>A factory for {@link ExecutionStrategy}.</p>
     */
    public static interface Factory
    {
        /**
         * <p>Creates a new {@link ExecutionStrategy}.</p>
         *
         * @param producer the execution strategy producer
         * @param executor the execution strategy executor
         * @return a new {@link ExecutionStrategy}
         */
        public ExecutionStrategy newExecutionStrategy(Producer producer, Executor executor);

        /**
         * @return the default {@link ExecutionStrategy}
         */
        public static Factory getDefault()
        {
            return DefaultExecutionStrategyFactory.INSTANCE;
        }
    }

    public static class DefaultExecutionStrategyFactory implements Factory
    {
        private static final Logger LOG = Log.getLogger(Factory.class);
        private static final Factory INSTANCE = new DefaultExecutionStrategyFactory();

        @Override
        public ExecutionStrategy newExecutionStrategy(Producer producer, Executor executor)
        {
            String strategy = System.getProperty(producer.getClass().getName() + ".ExecutionStrategy");
            if (strategy != null)
            {
                try
                {
                    Class<? extends ExecutionStrategy> c = Loader.loadClass(strategy);
                    Constructor<? extends ExecutionStrategy> m = c.getConstructor(Producer.class, Executor.class);
                    LOG.info("Use {} for {}", c.getSimpleName(), producer.getClass().getName());
                    return m.newInstance(producer, executor);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
            }

            return new ExecuteProduceConsume(producer, executor);
        }
    }
}
