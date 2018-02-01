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
public class EatWhatYouKill2 extends ContainerLifeCycle implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = Log.getLogger(EatWhatYouKill.class);

    private enum State { IDLE, PRODUCING, REPRODUCING }
    
    /* The modes this strategy can work in */
    /* The modes this strategy can work in */
    private abstract class Mode 
    {
        final String _mode;
        abstract boolean consume(Runnable task);
        
        Mode(String mode)
        {
            _mode = mode;
        }
        
        @Override
        public String toString()
        {
            return _mode;
        }
    }
    
    Mode PRODUCE_CONSUME = new Mode("PRODUCE_CONSUME")
    {
        boolean consume(Runnable task)
        {
            _pcMode.increment();
            task.run();
            return true;
        }
    };
    
    Mode PRODUCE_INVOKE_CONSUME = new Mode("PRODUCE_INVOKE_CONSUME")
    {
        boolean consume(Runnable task)
        {
            _picMode.increment();
            Invocable.invokeNonBlocking(task);
            return true;
        }
    };
    
    Mode PRODUCE_EXECUTE_CONSUME = new Mode("PRODUCE_EXECUTE_CONSUME")
    {
        boolean consume(Runnable task)
        {
            _pecMode.increment();
            _executor.execute(task);
            return true;
        }
    };
    
    Mode EXECUTE_PRODUCE_CONSUME = new Mode("EXECUTE_PRODUCE_CONSUME")
    {
        boolean consume(Runnable task)
        {
            _epcMode.increment();
            task.run();
            
            // Try to produce again?
            synchronized(EatWhatYouKill2.this)
            {
                switch (_state)
                {
                    case IDLE:
                        // Enter PRODUCING
                        _state = State.PRODUCING;
                        return true;

                    default:
                        return false;
                }
            }
        }
    };
        
    private final LongAdder _pcMode = new LongAdder();
    private final LongAdder _picMode = new LongAdder();
    private final LongAdder _pecMode = new LongAdder();
    private final LongAdder _epcMode = new LongAdder();
    private final Producer _producer;
    private final Executor _executor;
    private final TryExecutor _tryExecutor;
    private State _state = State.IDLE;
    private boolean _pending;

    public EatWhatYouKill2(Producer producer, Executor executor)
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
                    if (!_pending)
                    {
                        _pending = true;
                        execute = true;
                    }
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
        Boolean non_blocking_production;
        synchronized(this)
        {
            _pending = false;
            non_blocking_production = tryProduce();
        }

        if (non_blocking_production==Boolean.TRUE)
            doNonBlockingProduce();
        else if (non_blocking_production==Boolean.FALSE)
            doBlockingProduce();
    }
    
    @Override
    public void produce()
    {       
        Boolean non_blocking_production;
        synchronized(this)
        {
            non_blocking_production = tryProduce();
        }

        if (non_blocking_production==Boolean.TRUE)
            doNonBlockingProduce();
        else if (non_blocking_production==Boolean.FALSE)
            doBlockingProduce();
    }

    public void doBlockingProduce()
    {       
        while (true)
        {
            Runnable task = nextTask();
            if (task==null)
                break;

            // The calling thread can block, so we can choose between PC, PEC and EPC: modes,
            // based on the invocation type of the task and if a reserved thread is available
            Mode mode;
            switch(Invocable.getInvocationType(task))
            {
                case NON_BLOCKING:
                    mode = PRODUCE_CONSUME;
                    break;

                case BLOCKING:
                    // The task is blocking, so PC is not an option. Thus we choose 
                    // between EPC and PEC based on the availability of a reserved thread.
                    synchronized(this)
                    {
                        if (_pending)
                        {
                            _state = State.IDLE;
                            mode = EXECUTE_PRODUCE_CONSUME;
                        }
                        else if (_tryExecutor.tryExecute(this))
                        {
                            _pending = true;
                            _state = State.IDLE;
                            mode = EXECUTE_PRODUCE_CONSUME;
                        }
                        else
                        {
                            mode = PRODUCE_EXECUTE_CONSUME;
                        }   
                        break;
                    }

                case EITHER:
                {
                    // The task may be non blocking, so PC is an option. Thus we choose 
                    // between EPC and PC based on the availability of a reserved thread.
                    synchronized(this)
                    {
                        if (_pending)
                        {
                            _state = State.IDLE;
                            mode = EXECUTE_PRODUCE_CONSUME;
                        }
                        else if (_tryExecutor.tryExecute(this))
                        {
                            _pending = true;
                            _state = State.IDLE;
                            mode = EXECUTE_PRODUCE_CONSUME;
                        }
                        else
                        {
                            // PC mode, but we must consume with non-blocking invocation
                            // as we may be the last thread and we cannot block
                            mode = PRODUCE_INVOKE_CONSUME;
                        }
                    }
                }
                break;

                default:
                    throw new IllegalStateException();

            }

            if (LOG.isDebugEnabled())
                LOG.debug("dbp {} m={} t={}/{}", this, mode, task,Invocable.getInvocationType(task));

            // Consume or execute task
            try
            {
                // Consume or execute task
                if (!mode.consume(task))
                    return;
            }
            catch (RejectedExecutionException e)
            {
                rejectedExecution(task,e);
            }
            catch (Throwable e)
            {
                LOG.warn(e);
            }
        }
    }
    
    public void doNonBlockingProduce()
    {       
        while (true)
        {
            Runnable task = nextTask();
            if (task==null)
                break;
            
            // The calling thread cannot block, so we only have a choice between PC and PEC modes,
            // based on the invocation type of the task
            Mode mode;
            switch(Invocable.getInvocationType(task))
            {
                case NON_BLOCKING:
                    mode = PRODUCE_CONSUME;
                    break;

                case EITHER:
                    mode = PRODUCE_INVOKE_CONSUME;
                    break;

                default:
                    mode = PRODUCE_EXECUTE_CONSUME;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("dnbp {} m={} t={}/{}", this, mode, task,Invocable.getInvocationType(task));

            // Consume or execute task
            try
            {
                // Consume or execute task
                if (!mode.consume(task))
                    return;
            }
            catch (RejectedExecutionException e)
            {
                rejectedExecution(task,e);
            }
            catch (Throwable e)
            {
                LOG.warn(e);
            }
        }
    }
    
    private Boolean tryProduce()
    {
        switch (_state)
        {
            case IDLE:
                // Enter PRODUCING
                _state = State.PRODUCING;
                return Invocable.isNonBlockingInvocation()?Boolean.TRUE:Boolean.FALSE;

            case PRODUCING:
                // Keep other Thread producing
                _state = State.REPRODUCING;
                return null;

            default:
                return null;
        }
    }
    
    private Runnable nextTask()
    {
        while (isRunning())
        {
            try
            {
                Runnable task = _producer.produce();
                if (task!=null)
                    return task;
            }
            catch (Throwable e)
            {
                LOG.warn(e);
            }

            synchronized(this)
            {
                // Could another task just have been queued with a produce call?
                switch (_state)
                {
                    case PRODUCING:
                        _state = State.IDLE;
                        return null;

                    case REPRODUCING:
                        _state = State.PRODUCING;
                        continue;

                    default:
                        throw new IllegalStateException(toStringLocked());
                }                    
            }
        }
        return null;
    }
    
    private void rejectedExecution(Runnable task, RejectedExecutionException e)
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
        _picMode.reset();
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
        builder.append("/p=");
        builder.append(_pending);
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
