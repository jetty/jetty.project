//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ExecuteProduceRunTest
{
    private final Runnable NULLTASK = new Runnable()
    {
        @Override
        public void run()
        {            
        }
    };
    
    
    ExecuteProduceConsume _ewyk;
    final BlockingQueue<Runnable> _produce = new BlockingArrayQueue<>();
    final Queue<Runnable> _executions = new ConcurrentArrayQueue<>();
    volatile Thread _producer;
    
    @Before
    public void before()
    {
        _executions.clear();
        
        Producer producer = new Producer()
        {
            @Override
            public Runnable produce()
            {
                try
                {
                    _producer=Thread.currentThread();
                    Runnable task= _produce.take();
                    if (task==NULLTASK)
                        return null;
                    return task;
                }
                catch(InterruptedException e)
                {
                    e.printStackTrace();
                    return null;
                }
                finally
                {
                    _producer=null;
                }
            }
        };

        Executor executor =  new Executor()
        {
            @Override
            public void execute(Runnable task)
            {
                _executions.add(task);
            }
        };

        _ewyk = new ExecuteProduceConsume(producer,executor);
    }

    @After
    public void after()
    {
        // All done and checked
        assertThat(_produce.size(),equalTo(0));
        assertThat(_executions.size(),equalTo(0));
    }
    
    @Test
    public void testIdle()
    {
        _produce.add(NULLTASK);
        _ewyk.execute();
    }
    
    @Test
    public void testProduceOneNonBlockingTask()
    {
        Task t0 = new Task();
        _produce.add(t0);
        _produce.add(NULLTASK);
        _ewyk.execute();
        assertThat(t0.hasRun(),equalTo(true));
        Assert.assertEquals(_ewyk,_executions.poll());
    }
    
    @Test
    public void testProduceManyNonBlockingTask()
    {
        Task[] t = new Task[10];
        for (int i=0;i<t.length;i++)
        {
            t[i]=new Task();
            _produce.add(t[i]);
        }
        _produce.add(NULLTASK);
        _ewyk.execute();

        for (int i=0;i<t.length;i++)
            assertThat(t[i].hasRun(),equalTo(true));
        Assert.assertEquals(_ewyk,_executions.poll());

    }

    @Test
    public void testProduceOneBlockingTaskIdleByDispatch() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _produce.add(NULLTASK);
                _ewyk.execute();
            }
        };
        thread.start();
        
        // wait for execute thread to block in 
        t0.awaitRun();
        assertEquals(thread,t0.getThread());
        
        // Should have dispatched only one helper 
        assertEquals(_ewyk,_executions.poll());
        // which is make us idle
        _ewyk.run();
        assertThat(_ewyk.isIdle(),equalTo(true));

        
        // unblock task
        t0.unblock();
        // will run to completion because are already idle
        thread.join();
    }

    @Test
    public void testProduceOneBlockingTaskIdleByTask() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _produce.add(NULLTASK);
                _ewyk.execute();
            }
        };
        thread.start();
        
        // wait for execute thread to block in 
        t0.awaitRun();
        
        // Should have dispatched only one helper 
        Assert.assertEquals(_ewyk,_executions.poll());
               
        // unblock task
        t0.unblock();
        // will run to completion because are become idle
        thread.join();
        assertThat(_ewyk.isIdle(),equalTo(true));

        // because we are idle, dispatched thread is noop
        _ewyk.run();
    }

    @Test
    public void testBlockedInProduce() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _ewyk.execute();
            }
        };
        thread0.start();
        
        // wait for execute thread to block in task
        t0.awaitRun();
        assertEquals(thread0,t0.getThread());

        // Should have dispatched another helper 
        Assert.assertEquals(_ewyk,_executions.poll());
        
        // dispatched thread will block in produce 
        Thread thread1 = new Thread(_ewyk);
        thread1.start();
        
        // Spin
        while(_producer==null)
            Thread.yield();
        
        // thread1 is blocked in producing
        assertEquals(thread1,_producer);

        // because we are producing, any other dispatched threads are noops
        _ewyk.run();
        
        // ditto with execute
        _ewyk.execute();
        
        // Now if unblock the production by the dispatched thread
        final Task t1 = new Task(true);
        _produce.add(t1);

        // task will be run by thread1
        t1.awaitRun();
        assertEquals(thread1,t1.getThread());
        
        // and another thread will have been requested
        Assert.assertEquals(_ewyk,_executions.poll());
        
        // If we unblock t1, it will overtake t0 and try to produce again!
        t1.unblock();
        
        // Now thread1 is producing again
        while(_producer==null)
            Thread.yield();
        assertEquals(thread1,_producer);
        
        // If we unblock t0, it will decide it is not needed
        t0.unblock();
        thread0.join();
        
        // If the requested extra thread turns up, it is also noop because we are producing
        _ewyk.run();
        
        // Give the idle job
        _produce.add(NULLTASK);
        
        // Which will eventually idle the producer
        thread1.join();
        assertEquals(null,_producer);
    }

    @Test
    public void testExecuteWhileIdling() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _ewyk.execute();
            }
        };
        thread0.start();
        
        // wait for execute thread to block in task
        t0.awaitRun();
        assertEquals(thread0,t0.getThread());

        // Should have dispatched another helper 
        Assert.assertEquals(_ewyk,_executions.poll());
        
        // We will go idle when we next produce
        _produce.add(NULLTASK);
        
        // execute will return immediately because it did not yet see the idle.
        _ewyk.execute();
        
        // When we unblock t0, thread1 will see the idle, 
        t0.unblock();
        
        // but because there was a pending execute it will try producing again
        while(_producer==null)
            Thread.yield();
        assertEquals(thread0,_producer);

        // and will see new tasks 
        final Task t1 = new Task(true);
        _produce.add(t1);
        t1.awaitRun();
        assertThat(t1.getThread(),equalTo(thread0));

        // Should NOT have dispatched another helper, because the last is still pending
        assertThat(_executions.size(),equalTo(0));
        
        // When the dispatched thread turns up, it will see the second idle
        _produce.add(NULLTASK);
        _ewyk.run();
        assertThat(_ewyk.isIdle(),equalTo(true));
        
        // So that when t1 completes it does not produce again.
        t1.unblock();
        thread0.join();
    }
    

    public static class Task implements Runnable
    {
        final CountDownLatch _block = new CountDownLatch(1);
        final CountDownLatch _run = new CountDownLatch(1);
        volatile Thread _thread;

        public Task()
        {
            this(false);
        }
        
        public Task(boolean block)
        {
            if (!block)
                _block.countDown();
        }
        
        @Override
        public void run()
        {
            try
            {
                _thread=Thread.currentThread();
                _run.countDown();
                _block.await();
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
            finally
            {
                _thread=null;
            }
        }
        
        public boolean hasRun()
        {
            return _run.getCount()<=0;
        }
        
        public void awaitRun()
        {
            try
            {
                _run.await();
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
        }
        
        public void unblock()
        {
            _block.countDown();
        }
        
        public Thread getThread()
        {
            return _thread;
        }
    }
}
