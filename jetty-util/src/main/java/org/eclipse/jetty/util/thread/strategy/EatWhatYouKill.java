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
    private Condition _produce = _locker.newCondition();

    public EatWhatYouKill(Producer producer, Executor executor)
    {
        this(producer,executor,InvocationType.NON_BLOCKING,InvocationType.BLOCKING);
    }

    public EatWhatYouKill(Producer producer, Executor executor, InvocationType preferredExecution, InvocationType preferredInvocation)
    {
        this(producer,executor,preferredExecution,preferredInvocation,1);
    }
    
    public EatWhatYouKill(Producer producer, Executor executor, InvocationType preferredExecution, InvocationType preferredInvocation, int maxProducersPending )
    {
        _producer = producer;
        _pendingProducersMax = maxProducersPending;
        _executor = new InvocableExecutor(executor,preferredExecution,preferredInvocation);
    }

    @Override
    public void produce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} execute", this);

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
        if (dispatch)
            _executor.execute(_runProduce);
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
                if (_state == State.IDLE)                    
                {
                    _state = State.PRODUCING;
                    return true;
                } 
            }
            catch (InterruptedException e)
            {
                LOG.debug(e);
                // probably spurious, but we are not pending anymore
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
            boolean dispatch_new_producer = false;
            boolean eat_it;
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
                    keep_producing = false;
                    break producing;
                }
                
                // Will we eat our own kill?
                if (may_block_caller && Invocable.getInvocationType(task)==InvocationType.NON_BLOCKING)
                {
                    eat_it = true;
                    keep_producing = true;
                }
                else if (_pendingProducers==0 && _pendingProducersMax>0)
                {
                    keep_producing = true;
                    eat_it = false;
                    if ((_pendingProducersDispatched + _pendingProducers)<_pendingProducersMax)
                    {
                        _pendingProducersDispatched++;
                        dispatch_new_producer = true;
                    }
                }
                else
                {
                    eat_it = true;
                    keep_producing = false;
                    dispatch_new_producer = true;
                    _pendingProducersDispatched++;
                    _state = State.IDLE;
                    _pendingProducers--;
                    _produce.signal();
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("{} mbc={} dnp={} ei={} kp={}", this,may_block_caller,dispatch_new_producer,eat_it,keep_producing);

            // Run or execute the task.
            if (task != null)
            {;
                if (eat_it)
                    _executor.invoke(task);
                else
                    _executor.execute(task);
            }

            // If we need more producers
            if (dispatch_new_producer)
            {
                // Spawn a new thread to continue production by running the produce loop.
                _executor.execute(this);
            }

            // Once we have run the task, we can try producing again.
            if (keep_producing)
                continue producing;

            try (Lock locked = _locker.lock())
            {
                switch(_state)
                {
                    case IDLE:
                        _state = State.PRODUCING;
                        continue producing;

                    default: 
                        // Perhaps we can be a pending Producer?
                        if (pendingProducerWait())
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
            _pendingProducers=0;
            _produce.signalAll();
        }
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(super.toString());
        builder.append('/');
        try (Lock locked = _locker.lock())
        {
            builder.append(_state);
            builder.append('/');
            builder.append(_pendingProducers);
            builder.append('/');
            builder.append(_pendingProducersMax);
            builder.append('/');
        }
        builder.append(_producer);
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
