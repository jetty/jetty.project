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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * <p>A strategy where the thread that produces will always run the resulting task.</p>
 * <p>The strategy may then dispatch another thread to continue production.</p>
 * <p>The strategy is also known by the nickname 'eat what you kill', which comes from
 * the hunting ethic that says a person should not kill anything he or she does not
 * plan on eating. In this case, the phrase is used to mean that a thread should
 * not produce a task that it does not intend to run. By making producers run the
 * task that they have just produced avoids execution delays and avoids parallel slow
 * down by running the task in the same core, with good chances of having a hot CPU
 * cache. It also avoids the creation of a queue of produced tasks that the system
 * does not yet have capacity to consume, which can save memory and exert back
 * pressure on producers.</p>
 */
public class ExecuteProduceConsume extends ExecutingExecutionStrategy implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = Log.getLogger(ExecuteProduceConsume.class);

    private final Locker _locker = new Locker();
    private final Runnable _runExecute = new RunExecute();
    private final Producer _producer;
    private final ThreadPool _threadPool;
    private boolean _idle = true;
    private boolean _execute;
    private boolean _producing;
    private boolean _pending;
    private boolean _lowThreads;

    public ExecuteProduceConsume(Producer producer, Executor executor)
    {
        super(executor);
        this._producer = producer;
        _threadPool = executor instanceof ThreadPool ? (ThreadPool)executor : null;
    }

    @Deprecated
    public ExecuteProduceConsume(Producer producer, Executor executor, ExecutionStrategy lowResourceStrategy)
    {
        this(producer, executor);
    }

    @Override
    public void execute()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} execute", this);

        boolean produce = false;
        try (Lock locked = _locker.lock())
        {
            // If we are idle and a thread is not producing
            if (_idle)
            {
                if (_producing)
                    throw new IllegalStateException();

                // Then this thread will do the producing
                produce = _producing = true;
                // and we are no longer idle
                _idle = false;
            }
            else
            {
                // Otherwise, lets tell the producing thread
                // that it should call produce again before going idle
                _execute = true;
            }
        }

        if (produce)
            produceConsume();
    }

    @Override
    public void dispatch()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} spawning", this);
        boolean dispatch = false;
        try (Lock locked = _locker.lock())
        {
            if (_idle)
                dispatch = true;
            else
                _execute = true;
        }
        if (dispatch)
            execute(_runExecute);
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} run", this);
        boolean produce = false;
        try (Lock locked = _locker.lock())
        {
            _pending = false;
            if (!_idle && !_producing)
            {
                produce = _producing = true;
            }
        }

        if (produce)
            produceConsume();
    }

    private void produceConsume()
    {
        if (_threadPool != null && _threadPool.isLowOnThreads())
        {
            // If we are low on threads we must not produce and consume
            // in the same thread, but produce and execute to consume.
            if (!produceExecuteConsume())
                return;
        }
        executeProduceConsume();
    }

    public boolean isLowOnThreads()
    {
        return _lowThreads;
    }

    /**
     * @return true if we are still producing
     */
    private boolean produceExecuteConsume()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} enter low threads mode", this);
        _lowThreads = true;
        try
        {
            boolean idle = false;
            while (_threadPool.isLowOnThreads())
            {
                Runnable task = _producer.produce();
                if (LOG.isDebugEnabled())
                    LOG.debug("{} produced {}", _producer, task);

                if (task == null)
                {
                    // No task, so we are now idle
                    try (Lock locked = _locker.lock())
                    {
                        if (_execute)
                        {
                            _execute = false;
                            _producing = true;
                            _idle = false;
                            continue;
                        }

                        _producing = false;
                        idle = _idle = true;
                        break;
                    }
                }

                // Execute the task.
                executeProduct(task);
            }
            return !idle;
        }
        finally
        {
            _lowThreads = false;
            if (LOG.isDebugEnabled())
                LOG.debug("{} exit low threads mode", this);
        }
    }

    /**
     * <p>Only called when in {@link #isLowOnThreads() low threads mode}
     * to execute the task produced by the producer.</p>
     * <p>Because </p>
     * <p>If the task implements {@link Rejectable}, then {@link Rejectable#reject()}
     * is immediately called on the task object. If the task also implements
     * {@link Closeable}, then {@link Closeable#close()} is called on the task object.</p>
     * <p>If the task does not implement {@link Rejectable}, then it is
     * {@link #execute(Runnable) executed}.</p>
     *
     * @param task the produced task to execute
     */
    protected void executeProduct(Runnable task)
    {
        if (task instanceof Rejectable)
        {
            try
            {
                ((Rejectable)task).reject();
                if (task instanceof Closeable)
                    ((Closeable)task).close();
            }
            catch (Throwable x)
            {
                LOG.debug(x);
            }
        }
        else
        {
            execute(task);
        }
    }

    private void executeProduceConsume()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} produce enter", this);

        while (true)
        {
            // If we got here, then we are the thread that is producing.
            if (LOG.isDebugEnabled())
                LOG.debug("{} producing", this);

            Runnable task = _producer.produce();

            if (LOG.isDebugEnabled())
                LOG.debug("{} produced {}", this, task);

            boolean dispatch = false;
            try (Lock locked = _locker.lock())
            {
                // Finished producing
                _producing = false;

                // Did we produced a task?
                if (task == null)
                {
                    // There is no task.
                    // Could another one just have been queued with an execute?
                    if (_execute)
                    {
                        _idle = false;
                        _producing = true;
                        _execute = false;
                        continue;
                    }

                    // ... and no additional calls to execute, so we are idle
                    _idle = true;
                    break;
                }

                // We have a task, which we will run ourselves,
                // so if we don't have another thread pending
                if (!_pending)
                {
                    // dispatch one
                    dispatch = _pending = true;
                }

                _execute = false;
            }

            // If we became pending
            if (dispatch)
            {
                // Spawn a new thread to continue production by running the produce loop.
                if (LOG.isDebugEnabled())
                    LOG.debug("{} dispatch", this);
                if (!execute(this))
                    task = null;
            }

            // Run the task.
            if (LOG.isDebugEnabled())
                LOG.debug("{} run {}", this, task);
            if (task != null)
                task.run();
            if (LOG.isDebugEnabled())
                LOG.debug("{} ran {}", this, task);

            // Once we have run the task, we can try producing again.
            try (Lock locked = _locker.lock())
            {
                // Is another thread already producing or we are now idle?
                if (_producing || _idle)
                    break;
                _producing = true;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} produce exit", this);
    }

    public Boolean isIdle()
    {
        try (Lock locked = _locker.lock())
        {
            return _idle;
        }
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("EPC ");
        try (Lock locked = _locker.lock())
        {
            builder.append(_idle ? "Idle/" : "");
            builder.append(_producing ? "Prod/" : "");
            builder.append(_pending ? "Pend/" : "");
            builder.append(_execute ? "Exec/" : "");
        }
        builder.append(_producer);
        return builder.toString();
    }

    private class RunExecute implements Runnable
    {
        @Override
        public void run()
        {
            execute();
        }
    }

    public static class Factory implements ExecutionStrategy.Factory
    {
        @Override
        public ExecutionStrategy newExecutionStrategy(Producer producer, Executor executor)
        {
            return new ExecuteProduceConsume(producer, executor);
        }
    }
}
