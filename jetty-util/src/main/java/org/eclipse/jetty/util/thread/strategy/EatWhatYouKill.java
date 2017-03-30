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

import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocableExecutor;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;

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
 * 
 */
public class EatWhatYouKill extends AbstractLifeCycle implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = Log.getLogger(EatWhatYouKill.class);

    enum State { IDLE, PRODUCING, REPRODUCING };
    
    private final Locker _locker = new Locker();
    private State _state = State.IDLE;
    private final Runnable _runProduce = new RunProduce();
    private final Producer _producer;
    private final InvocableExecutor _executor;
    private int _pendingProducersMax;
    private int _pendingProducers;
    private int _pendingProducersDispatched;
    private int _pendingProducersSignalled;
    private Condition _produce = _locker.newCondition();

    public EatWhatYouKill(Producer producer, Executor executor)
    {
        this(producer,executor,InvocationType.NON_BLOCKING,InvocationType.BLOCKING);
    }

    public EatWhatYouKill(Producer producer, Executor executor, int maxProducersPending )
    {
        this(producer,executor,InvocationType.NON_BLOCKING,InvocationType.BLOCKING);
    }
    
    public EatWhatYouKill(Producer producer, Executor executor, InvocationType preferredInvocationPEC, InvocationType preferredInvocationEPC)
    {
        this(producer,executor,preferredInvocationPEC,preferredInvocationEPC,Integer.getInteger("org.eclipse.jetty.util.thread.strategy.EatWhatYouKill.maxProducersPending",1));
    }
    
    public EatWhatYouKill(Producer producer, Executor executor, InvocationType preferredInvocationPEC, InvocationType preferredInvocationEPC, int maxProducersPending )
    {
        _producer = producer;
        _pendingProducersMax = maxProducersPending;
        _executor = new InvocableExecutor(executor,preferredInvocationPEC,preferredInvocationEPC);
    }

    @Override
    public void produce()
    {
        boolean produce;
        try (Lock locked = _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                    _state = State.PRODUCING;
                    produce = true;
                    break;
                    
                case PRODUCING:
                    _state = State.REPRODUCING;
                    produce = false;
                    break;
                    
                default:     
                    produce = false;   
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} execute {}", this, produce);

        if (produce)
            doProduce();
    }

    @Override
    public void dispatch()
    {
        boolean dispatch = false;
        try (Lock locked = _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                    dispatch = true;
                    break;
                    
                case PRODUCING:
                    _state = State.REPRODUCING;
                    dispatch = false;
                    break;
                    
                default:     
                    dispatch = false;   
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} dispatch {}", this, dispatch);
        if (dispatch)
            _executor.execute(_runProduce,InvocationType.BLOCKING);
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} run", this);
        if (!isRunning())
            return;
        boolean producing = false;
        try (Lock locked = _locker.lock())
        {
            _pendingProducersDispatched--;
            _pendingProducers++;

            loop: while (isRunning())
            {
                try
                {
                    _produce.await();

                    if (_pendingProducersSignalled==0)
                    {
                        // spurious wakeup!
                        continue loop;
                    } 

                    _pendingProducersSignalled--;
                    if (_state == State.IDLE)                    
                    {
                        _state = State.PRODUCING;
                        producing = true;
                    } 
                }
                catch (InterruptedException e)
                {
                    LOG.debug(e);
                    _pendingProducers--;
                }
               
                break loop;
            }     
        }

        if (producing)
            doProduce();
    }

    private void doProduce()
    {
        boolean may_block_caller = !Invocable.isNonBlockingInvocation();
        if (LOG.isDebugEnabled())
            LOG.debug("{} produce {}", this,may_block_caller?"non-blocking":"blocking");

        producing: while (isRunning())
        {
            // If we got here, then we are the thread that is producing.
            Runnable task = _producer.produce();

            boolean new_pending_producer;
            boolean run_task_ourselves;
            boolean keep_producing;
            
            StringBuilder state = null;
            
            try (Lock locked = _locker.lock())
            {
                if (LOG.isDebugEnabled())
                {
                    state = new StringBuilder();
                    getString(state);
                    getState(state);
                    state.append("->");
                }
                
                // Did we produced a task?
                if (task == null)
                {
                    // There is no task.
                    // Could another one just have been queued with a produce call?
                    if (_state==State.REPRODUCING)
                    {
                        _state = State.PRODUCING;
                        continue producing;
                    }

                    // ... and no additional calls to execute, so we are idle
                    _state = State.IDLE;
                    break producing;
                }
                
                // Will we eat our own kill - ie consume the task we just produced?
                if (Invocable.getInvocationType(task)==InvocationType.NON_BLOCKING)
                {
                    // ProduceConsume
                    run_task_ourselves = true;
                    keep_producing = true;
                    new_pending_producer = false;
                }
                else if (may_block_caller && (_pendingProducers>0 || _pendingProducersMax==0))
                {
                    // ExecuteProduceConsume (eat what we kill!)
                    run_task_ourselves = true;
                    keep_producing = false;
                    new_pending_producer = true;
                    _pendingProducersDispatched++;
                    _state = State.IDLE;
                    _pendingProducers--;
                    _pendingProducersSignalled++;
                    _produce.signal();
                }
                else
                {
                    // ProduceExecuteConsume
                    keep_producing = true;
                    run_task_ourselves = false;
                    new_pending_producer = (_pendingProducersDispatched + _pendingProducers)<_pendingProducersMax;
                    if (new_pending_producer)
                        _pendingProducersDispatched++;
                }
                
                if (LOG.isDebugEnabled())
                    getState(state);
                
            }
            
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} {} {}",
                    state,
                    run_task_ourselves?(new_pending_producer?"EPC!":"PC"):"PEC",
                    task);
            }

            if (new_pending_producer)
                // Spawn a new thread to continue production by running the produce loop.
                _executor.execute(this);
            
            // Run or execute the task.
            if (run_task_ourselves)
                _executor.invoke(task);
            else
                _executor.execute(task);
           
            // Once we have run the task, we can try producing again.
            if (keep_producing)
                continue producing;

            try (Lock locked = _locker.lock())
            {
                if (_state==State.IDLE)
                {
                    _state = State.PRODUCING;
                    continue producing;
                }
            }

            break producing;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} produce exit",this);
    }

    public Boolean isIdle()
    {
        try (Lock locked = _locker.lock())
        {
            return _state==State.IDLE;
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        try (Lock locked = _locker.lock())
        {
            _pendingProducersSignalled=_pendingProducers+_pendingProducersDispatched;
            _pendingProducers=0;
            _produce.signalAll();
        }
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        getString(builder);
        try (Lock locked = _locker.lock())
        {
            getState(builder);
        }
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
        builder.append(_pendingProducers);
        builder.append('/');
        builder.append(_pendingProducersMax);
    }

    private class RunProduce implements Runnable
    {
        @Override
        public void run()
        {
            produce();
        }
    }
}
