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
        this(producer,executor,preferredInvocationPEC,preferredInvocationEPC,1);
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
            produceConsume();
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
        boolean producing;
        try (Lock locked = _locker.lock())
        {
            _pendingProducersDispatched--;
            producing = pendingProducerWait();
        }

        if (producing)
            produceConsume();
    }
    
    private boolean pendingProducerWait()
    {
        if (_pendingProducers<_pendingProducersMax)
        {
            try
            {
                _pendingProducers++;

                _produce.await();
                if (_pendingProducersSignalled==0)
                {
                    // spurious wakeup!
                    if (LOG.isDebugEnabled() && isRunning())
                        LOG.debug("{} SPURIOUS WAKEUP",this);
                    _pendingProducers--;
                } 
                else
                {
                    _pendingProducersSignalled--;
                    if (_state == State.IDLE)                    
                    {
                        _state = State.PRODUCING;
                        return true;
                    } 
                }
            }
            catch (InterruptedException e)
            {
                LOG.debug(e);
                _pendingProducers--;
            }
        }       
        return false;
    }

    private void produceConsume()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} produce enter", this);

        producing: while (isRunning())
        {
            // If we got here, then we are the thread that is producing.
            if (LOG.isDebugEnabled())
                LOG.debug("{} producing", this);

            Runnable task = _producer.produce();

            if (LOG.isDebugEnabled())
                LOG.debug("{} produced {}", this, task);

            boolean may_block_caller = !Invocable.isNonBlockingInvocation();
            boolean new_pending_producer;
            boolean run_task_ourselves;
            boolean keep_producing;
            
            try (Lock locked = _locker.lock())
            {
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
            }
            if (LOG.isDebugEnabled())
                LOG.debug("{} mbc={} dnp={} run={} kp={}", this,may_block_caller,new_pending_producer,run_task_ourselves,keep_producing);

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
        builder.append(getClass().getSimpleName());
        builder.append('@');
        builder.append(Integer.toHexString(hashCode()));
        builder.append('/');
        builder.append(_producer);
        builder.append('/');
        try (Lock locked = _locker.lock())
        {
            builder.append(_state);
            builder.append('/');
            builder.append(_pendingProducers);
            builder.append('/');
            builder.append(_pendingProducersMax);
        }
        return builder.toString();
    }

    private class RunProduce implements Runnable
    {
        @Override
        public void run()
        {
            produce();
        }
    }

    public static class Factory implements ExecutionStrategy.Factory
    {
        @Override
        public ExecutionStrategy newExecutionStrategy(Producer producer, Executor executor)
        {
            return new EatWhatYouKill(producer, executor);
        }
    }
}
