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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An Executor using preallocated/reserved Threads from a wrapped Executor.
 * <p>Calls to {@link #execute(Runnable)} on a {@link ReservedThreadExecutor} will either succeed
 * with a Thread immediately being assigned the Runnable task, or fail if no Thread is
 * available. Threads are preallocated up to the capacity from a wrapped {@link Executor}.
 */
public class ReservedThreadExecutor extends AbstractLifeCycle implements Executor
{
    private static final Logger LOG = Log.getLogger(ReservedThreadExecutor.class);
    
    private final Executor _executor;
    private final Locker _locker = new Locker();
    private final ReservedThread[] _queue;
    private int _head;
    private int _size;
    private int _pending;
    
    public ReservedThreadExecutor(Executor executor)
    {
        this(executor,1);
    }

    /**
     * @param executor The executor to use to obtain threads
     * @param capacity The number of threads to preallocate. If less than 0 then capacity
     * is calculated based on a heuristic from the number of available processors and 
     * thread pool size.
     */
    public ReservedThreadExecutor(Executor executor,int capacity)
    {
        _executor = executor;
        
        if (capacity < 0)
        {
            if (executor instanceof ThreadPool)
            {
                int threads = ((ThreadPool)executor).getThreads();
                int cpus = Runtime.getRuntime().availableProcessors();
                capacity = Math.max(1,Math.min(cpus,threads/8));
            }
            else
            {
                capacity = Runtime.getRuntime().availableProcessors();
            }
        }
        
        _queue = new ReservedThread[capacity];
    }

    public Executor getExecutor()
    {
        return _executor;
    }
    
    public int getCapacity()
    {
        return _queue.length;
    }
    
    public int getPreallocated()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _size;
        }
    }
    
    @Override
    public void doStart() throws Exception
    {
        try (Locker.Lock lock = _locker.lock())
        {
            _head = _size = _pending = 0;
            while (_pending<_queue.length)
            {
                _executor.execute(new ReservedThread());
                _pending++;
            }
        }
    }
    
    @Override
    public void doStop() throws Exception
    {
        try (Locker.Lock lock = _locker.lock())
        {
            while (_size>0)
            {
                ReservedThread thread = _queue[_head];
                _queue[_head] = null;
                _head = (_head+1)%_queue.length;
                _size--;
                thread._wakeup.signal();
            }
        }
    } 
    
    @Override
    public void execute(Runnable task) throws RejectedExecutionException
    {
        if (!tryExecute(task))
            throw new RejectedExecutionException();
    }
    
    /**
     * @param task The task to run
     * @return True iff a reserved thread was available and has been assigned the task to run.
     */
    public boolean tryExecute(Runnable task)
    {
        if (task==null)
            return false;
        
        try (Locker.Lock lock = _locker.lock())
        {
            if (_size==0)
            {
                if (_pending<_queue.length)
                {
                    _executor.execute(new ReservedThread());
                    _pending++;
                }
                return false;
            }
            
            ReservedThread thread = _queue[_head];
            _queue[_head] = null;
            _head = (_head+1)%_queue.length;
            _size--;
            
            if (_size==0 && _pending<_queue.length)
            {
                _executor.execute(new ReservedThread());
                _pending++;
            }
            
            thread._task = task;
            thread._wakeup.signal();
            
            return true;
        }
        catch(RejectedExecutionException e)
        {
            LOG.ignore(e);
            return false;
        }
    }

    private class ReservedThread implements Runnable
    {
        private Condition _wakeup = null;
        private Runnable _task = null;
        
        private void reservedWait() throws InterruptedException
        {
            _wakeup.await();
        }
        
        @Override
        public void run()
        {
            while (true)
            {
                Runnable task = null;
             
                try (Locker.Lock lock = _locker.lock())
                {
                    // if this is our first loop, decrement pending count
                    if (_wakeup==null)
                    {
                        _pending--;
                        _wakeup = _locker.newCondition();
                    }
                    
                    // Exit if no longer running or there now too many preallocated threads
                    if (!isRunning() || _size>=_queue.length)
                        break;
                    
                    // Insert ourselves in the queue
                    _queue[(_head+_size++)%_queue.length] = this;

                    // Wait for a task, ignoring spurious interrupts
                    do
                    {
                        try
                        {
                            reservedWait();
                            task = _task;
                            _task = null;
                        }
                        catch (InterruptedException e)
                        {
                            LOG.ignore(e);
                        }
                    }
                    while (isRunning() && task==null);
                }

                // Run any task 
                if (task!=null)
                {
                    try
                    {
                        task.run();
                    }
                    catch (Exception e)
                    {
                        LOG.warn(e);
                        break;
                    }
                }
            }
        }
    }
    
    @Override
    public String toString()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return String.format("%s{s=%d,p=%d}",super.toString(),_size,_pending);
        }
    }
}
