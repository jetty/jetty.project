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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/* ------------------------------------------------------------ */
/** Strategies to execute Producers
 */
public abstract class ExecutionStrategy implements Runnable
{    
    public interface Producer
    {
        /**
         * Produce a task to run
         * @return A task to run or null if we are complete.
         */
        Runnable produce();

        /**
         * Check if there is more to produce. This method may not return valid 
         * results until {@link #produce()} has been called.
         * @return true if this Producer may produce more tasks from {@link #produce()}
         */
        boolean isMoreToProduce();
        
        /**
         * Called to signal production is completed
         */
        void onCompleted();
    }

    protected final Producer _producer;
    protected final Executor _executor;

    protected ExecutionStrategy(Producer producer, Executor executor)
    {
        _producer=producer;
        _executor=executor;
    }
    
    /* ------------------------------------------------------------ */
    /** Simple iterative strategy.
     * Iterate over production until complete and execute each task.
     */
    public static class Iterative extends ExecutionStrategy
    {
        public Iterative(Producer producer, Executor executor)
        {
            super(producer,executor);
        }
        
        public void run()
        {
            try
            {
                // Iterate until we are complete
                loop: while (true)
                {
                    // produce a task
                    Runnable task=_producer.produce(); 

                    // if there is no task, break the loop
                    if (task==null)
                        break loop;

                    // If we are still producing,
                    if (_producer.isMoreToProduce())
                        // execute the task
                        _executor.execute(task);
                    else
                    {
                        // last task so we can run ourselves
                        task.run();
                        break loop;
                    }
                }
            }
            finally
            {
                _producer.onCompleted();
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * A Strategy that allows threads to run the tasks that they have produced,
     * so execution is done with a hot cache (ie threads eat what they kill).
     */
    public static class EatWhatYouKill extends ExecutionStrategy
    {
        private final AtomicInteger _threads = new AtomicInteger(0);
        private final AtomicReference<Boolean> _producing = new AtomicReference<Boolean>(Boolean.FALSE);
        private volatile boolean _dispatched;

        public EatWhatYouKill(Producer producer, Executor executor)
        {
            super(producer,executor);
        }
        
        public void run()
        {
            _dispatched=false;
            // count the dispatched threads
            _threads.incrementAndGet();
            try
            {
                boolean complete=false;
                loop: while (!complete)
                {
                    // If another thread is already producing, 
                    if (!_producing.compareAndSet(false,true))
                        // break the loop even if not complete
                        break loop;

                    // If we got here, then we are the thread that is producing
                    Runnable task=null;
                    try
                    {
                        task=_producer.produce(); 
                        complete=task==null || _producer.isMoreToProduce();
                    }
                    finally
                    {
                        _producing.set(false);
                    }

                    // then we may need another thread to keep producing
                    if (!complete && !_dispatched)
                    {
                        // Dispatch a thread to continue producing
                        _dispatched=true;
                        _executor.execute(this);
                    }
                    
                    // If there is a task, 
                    if (task!=null)
                        // run the task
                        task.run();
                }

            }
            finally
            {
                // If we were the last thread, signal completion
                if (_threads.decrementAndGet()==0)
                    _producer.onCompleted();
            }
        }
    }

}
