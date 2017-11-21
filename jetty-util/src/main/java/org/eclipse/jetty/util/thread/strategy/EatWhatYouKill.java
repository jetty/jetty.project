//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;

/**
 * <p>A strategy where the thread that produces will run the resulting task if it
 * is possible to do so without thread starvation.</p>
 *
 * <p>This strategy preemptively dispatches a thread as a pending producer, so that
 * when a thread produces a task it can immediately run the task and let the pending
 * producer thread take over producing.  If necessary another thread will be dispatched
 * to replace the pending producing thread.   When operating in this pattern, the
 * sub-strategy is called Execute Produce Consume (EPC)
 * </p>
 * <p>However, if the task produced uses the {@link Invocable} API to indicate that
 * it will not block, then the strategy will run it directly, regardless of the
 * presence of a pending producing thread and then resume producing after the
 * task has completed. This sub-strategy is also used if the strategy has been
 * configured with a maximum of 0 pending threads and the thread currently producing
 * does not use the {@link Invocable} API to indicate that it will not block.
 * When operating in this pattern, the sub-strategy is called
 * ProduceConsume (PC).
 * </p>
 * <p>If there is no pending producer thread available and if the task has not
 * indicated it is non-blocking, then this strategy will dispatch the execution of
 * the task and immediately continue producing.  When operating in this pattern, the
 * sub-strategy is called ProduceExecuteConsume (PEC).
 * </p>
 */
@ManagedObject("eat what you kill execution strategy")
public class EatWhatYouKill extends ContainerLifeCycle implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = Log.getLogger(EatWhatYouKill.class);

    private enum State { IDLE, PRODUCING, REPRODUCING }

    private final Locker _locker = new Locker();
    private final LongAdder _nonBlocking = new LongAdder();
    private final LongAdder _blocking = new LongAdder();
    private final LongAdder _executed = new LongAdder();
    private final Producer _producer;
    private final Executor _executor;
    private final ReservedThreadExecutor _producers;
    private State _state = State.IDLE;

    public EatWhatYouKill(Producer producer, Executor executor)
    {
        this(producer,executor,new ReservedThreadExecutor(executor,1));
    }

    public EatWhatYouKill(Producer producer, Executor executor, int maxReserved)
    {
        this(producer,executor,new ReservedThreadExecutor(executor,maxReserved));
    }

    public EatWhatYouKill(Producer producer, Executor executor, ReservedThreadExecutor producers)
    {
        _producer = producer;
        _executor = executor;
        _producers = producers;
        addBean(_producer);
        if (LOG.isDebugEnabled())
            LOG.debug("{} created", this);
    }

    @Override
    public void dispatch()
    {
        boolean execute = false;
        try (Lock locked = _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                    execute = true;
                    break;

                case PRODUCING:
                    _state = State.REPRODUCING;
                    break;

                default:
                    break;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} dispatch {}", this, execute);
        if (execute)
            _executor.execute(this);
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} run", this);
        produce();
    }

    @Override
    public void produce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} produce", this);
        boolean reproduce = true;
        while(isRunning() && tryProduce(reproduce) && doProduce())
            reproduce = false;
    }

    public boolean tryProduce(boolean reproduce)
    {
        boolean producing = false;
        try (Lock locked = _locker.lock())
        {
            switch (_state)
            {
                case IDLE:
                    // Enter PRODUCING
                    _state = State.PRODUCING;
                    producing = true;
                    break;

                case PRODUCING:
                    // Keep other Thread producing
                    if (reproduce)
                        _state = State.REPRODUCING;
                    break;

                default:
                    break;
            }
        }
        return producing;
    }

    public boolean doProduce()
    {
        boolean producing = true;
        while (isRunning() && producing)
        {
            // If we got here, then we are the thread that is producing.
            Runnable task = null;
            try
            {
                task = _producer.produce();
            }
            catch(Throwable e)
            {
                LOG.warn(e);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} t={}/{}",this,task,Invocable.getInvocationType(task));

            if (task==null)
            {
                try (Lock locked = _locker.lock())
                {
                    // Could another one just have been queued with a produce call?
                    if (_state==State.REPRODUCING)
                    {
                        _state = State.PRODUCING;
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} IDLE",toStringLocked());
                        _state = State.IDLE;
                        producing = false;
                    }
                }
            }
            else
            {
                boolean consume;
                if (Invocable.getInvocationType(task) == InvocationType.NON_BLOCKING)
                {
                    // PRODUCE CONSUME (EWYK!)
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} PC t={}", this, task);
                    consume = true;
                    _nonBlocking.increment();
                }
                else
                {
                    try (Lock locked = _locker.lock())
                    {
                        if (_producers.tryExecute(this))
                        {
                            // EXECUTE PRODUCE CONSUME!
                            // We have executed a new Producer, so we can EWYK consume
                            _state = State.IDLE;
                            producing = false;
                            consume = true;
                            _blocking.increment();
                        }
                        else
                        {
                            // PRODUCE EXECUTE CONSUME!
                            consume = false;
                            _executed.increment();
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("{} {} t={}", this, consume ? "EPC" : "PEC", task);
                }

                // Consume or execute task
                try
                {
                    if (consume)
                        task.run();
                    else
                        _executor.execute(task);
                }
                catch (RejectedExecutionException e)
                {
                    LOG.warn(e);
                    if (task instanceof Closeable)
                    {
                        try
                        {
                            ((Closeable)task).close();
                        }
                        catch (Throwable e2)
                        {
                            LOG.ignore(e2);
                        }
                    }
                }
                catch (Throwable e)
                {
                    LOG.warn(e);
                }
            }
        }

        return producing;
    }

    @ManagedAttribute(value = "number of non blocking tasks consumed", readonly = true)
    public long getNonBlockingTasksConsumed()
    {
        return _nonBlocking.longValue();
    }

    @ManagedAttribute(value = "number of blocking tasks consumed", readonly = true)
    public long getBlockingTasksConsumed()
    {
        return _blocking.longValue();
    }

    @ManagedAttribute(value = "number of blocking tasks executed", readonly = true)
    public long getBlockingTasksExecuted()
    {
        return _executed.longValue();
    }

    @ManagedAttribute(value = "whether this execution strategy is idle", readonly = true)
    public boolean isIdle()
    {
        try (Lock locked = _locker.lock())
        {
            return _state==State.IDLE;
        }
    }

    @ManagedOperation(value = "resets the task counts", impact = "ACTION")
    public void reset()
    {
        _nonBlocking.reset();
        _blocking.reset();
        _executed.reset();
    }

    public String toString()
    {
        try (Lock locked = _locker.lock())
        {
            return toStringLocked();
        }
    }

    public String toStringLocked()
    {
        StringBuilder builder = new StringBuilder();
        getString(builder);
        getState(builder);
        return builder.toString();
    }

    private void getString(StringBuilder builder)
    {
        builder.append(getClass().getSimpleName());
        builder.append('@');
        builder.append(Integer.toHexString(hashCode()));
        builder.append('/');
        builder.append(_producer);
        builder.append('/');
    }

    private void getState(StringBuilder builder)
    {
        builder.append(_state);
        builder.append('/');
        builder.append(_producers);
    }
}
