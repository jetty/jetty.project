//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
import org.eclipse.jetty.util.thread.TryExecutor;

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

    private enum State { IDLE, PENDING, PRODUCING, REPRODUCING }
    
    /* The modes this strategy can work in */
    private enum Mode 
    { 
        PRODUCE_CONSUME, 
        PRODUCE_INVOKE_CONSUME, // This is PRODUCE_CONSUME an EITHER task with NON_BLOCKING invocation
        PRODUCE_EXCECUTE_CONSUME, 
        EXECUTE_PRODUCE_CONSUME // Eat What You Kill!
    }; 
    
    private final LongAdder _pcMode = new LongAdder();
    private final LongAdder _picMode = new LongAdder();
    private final LongAdder _pecMode = new LongAdder();
    private final LongAdder _epcMode = new LongAdder();
    private final Producer _producer;
    private final Executor _executor;
    private final TryExecutor _tryExecutor;
    private State _state = State.IDLE;

    public EatWhatYouKill(Producer producer, Executor executor)
    {
        _producer = producer;
        _executor = executor;
        _tryExecutor = TryExecutor.asTryExecutor(executor);
        addBean(_producer);
        addBean(_tryExecutor);
        if (LOG.isDebugEnabled())
            LOG.debug("{} created", this);        
    }

    @Override
    public void dispatch()
    {
        boolean execute = false;
        synchronized(this)
        {
            switch(_state)
            {
                case IDLE:
                    execute = true;
                    _state = State.PENDING;
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
        synchronized(this)
        {
            switch (_state)
            {
                case IDLE:
                case PENDING:
                    // Enter PRODUCING
                    _state = State.PRODUCING;
                    break;

                case PRODUCING:
                    // Keep other Thread producing
                    _state = State.REPRODUCING;
                    return;

                default:
                    return;
            }
        }
        
        boolean non_blocking = Invocable.isNonBlockingInvocation();
        while (isRunning())
        {
            Runnable task;
            try
            {
                task = _producer.produce();
            }
            catch (Throwable e)
            {
                LOG.warn(e);
                task = null;
            }

            if (task==null)
            {
                synchronized(this)
                {
                    // Could another task just have been queued with a produce call?
                    switch (_state)
                    {
                        case PRODUCING:
                            _state = State.IDLE;
                            return;
                           
                        case REPRODUCING:
                            _state = State.PRODUCING;
                            continue;
                            
                        default:
                            throw new IllegalStateException(toStringLocked());
                    }                    
                }
            }

            Mode mode;
            if (non_blocking)
            {
                // The calling thread cannot block, so we only have a choice between PC and PEC modes,
                // based on the invocation type of the task
                switch(Invocable.getInvocationType(task))
                {
                    case NON_BLOCKING:
                        mode = Mode.PRODUCE_CONSUME;
                        break;

                    case EITHER:
                        mode = Mode.PRODUCE_INVOKE_CONSUME;
                        break;

                    default:
                        mode = Mode.PRODUCE_EXCECUTE_CONSUME;
                }
            }
            else
            {
                // The calling thread can block, so we can choose between PC, PEC and EPC: modes,
                // based on the invocation type of the task and if a reserved thread is available
                switch(Invocable.getInvocationType(task))
                {
                    case NON_BLOCKING:
                        mode = Mode.PRODUCE_CONSUME;
                        break;

                    case BLOCKING:
                        // The task is blocking, so PC is not an option. Thus we choose 
                        // between EPC and PEC based on the availability of a reserved thread.
                        synchronized(this)
                        {
                            if (_tryExecutor.tryExecute(this))
                            {
                                // EPC mode!
                                _state = State.PENDING;
                                mode = Mode.EXECUTE_PRODUCE_CONSUME;
                            }
                            else
                            {
                                mode = Mode.PRODUCE_EXCECUTE_CONSUME;
                            }   
                            break;
                        }

                    case EITHER:
                    {
                        // The task may be non blocking, so PC is an option. Thus we choose 
                        // between EPC and PC based on the availability of a reserved thread.
                        synchronized(this)
                        {
                            if (_tryExecutor.tryExecute(this))
                            {
                                // Normal EPC mode!
                                _state = State.PENDING;
                                mode = Mode.EXECUTE_PRODUCE_CONSUME;
                            }
                            else
                            {
                                // PC mode, but we must consume with non-blocking invocation
                                // as we may be the last thread and we cannot block
                                mode = Mode.PRODUCE_INVOKE_CONSUME;
                            }
                        }
                    }
                    break;

                    default:
                        throw new IllegalStateException();
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} m={} t={}/{}", this, mode, task,Invocable.getInvocationType(task));

            // Consume or execute task
            try
            {
                switch(mode)
                {
                    case PRODUCE_CONSUME:
                        _pcMode.increment();
                        task.run();
                        break;

                    case PRODUCE_INVOKE_CONSUME:
                        _picMode.increment();
                        Invocable.invokeNonBlocking(task);
                        break;

                    case PRODUCE_EXCECUTE_CONSUME:
                        _pecMode.increment();
                        _executor.execute(task);
                        break;

                    case EXECUTE_PRODUCE_CONSUME:
                        _epcMode.increment();
                        task.run();
                        return;
                }
            }
            catch (RejectedExecutionException e)
            {
                if (isRunning())
                    LOG.warn(e);
                else
                    LOG.ignore(e);
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

    @ManagedAttribute(value = "number of non tasks consumed with PC", readonly = true)
    public long getPCTasksConsumed()
    {
        return _pcMode.longValue();
    }
    
    @ManagedAttribute(value = "number of tasks executed with PIC mode", readonly = true)
    public long getPICTasksExecuted()
    {
        return _picMode.longValue();
    }
    
    @ManagedAttribute(value = "number of tasks executed with PEC mode", readonly = true)
    public long getPECTasksExecuted()
    {
        return _pecMode.longValue();
    }

    @ManagedAttribute(value = "number of tasks consumed with EPC mode", readonly = true)
    public long getEPCTasksConsumed()
    {
        return _epcMode.longValue();
    }

    @ManagedAttribute(value = "whether this execution strategy is idle", readonly = true)
    public boolean isIdle()
    {
        synchronized(this)
        {
            return _state==State.IDLE;
        }
    }

    @ManagedOperation(value = "resets the task counts", impact = "ACTION")
    public void reset()
    {
        _pcMode.reset();
        _epcMode.reset();
        _pecMode.reset();
    }

    public String toString()
    {
        synchronized(this)
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
        builder.append(_tryExecutor);
        builder.append("[pc=");
        builder.append(getPCTasksConsumed());
        builder.append(",pic=");
        builder.append(getPICTasksExecuted());
        builder.append(",pec=");
        builder.append(getPECTasksExecuted());
        builder.append(",epc=");
        builder.append(getEPCTasksConsumed());
        builder.append("]");
        builder.append("@");
        builder.append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()));
    }
}
