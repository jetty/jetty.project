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


import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ExecutionStrategyTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data()
    {
        return Arrays.asList(new Object[][]{
            {ProduceExecuteConsume.class},
            {ExecuteProduceConsume.class},
            {EatWhatYouKill.class}
        });
    }

    QueuedThreadPool threads = new QueuedThreadPool(20);
    Class<? extends ExecutionStrategy> _strategyClass;
    ExecutionStrategy _strategy;

    public ExecutionStrategyTest(Class<? extends ExecutionStrategy> strategy)
    {
        _strategyClass = strategy;
    }
    
    void newExecutionStrategy(Producer producer, Executor executor) throws Exception
    {
        _strategy = _strategyClass.getConstructor(Producer.class,Executor.class).newInstance(producer,executor);
        if (_strategy instanceof LifeCycle)
            ((LifeCycle)_strategy).start();
    }
    
    @Before
    public void before() throws Exception
    {
        threads.start();
    }
    
    @After
    public void after() throws Exception
    {
        if (_strategy instanceof LifeCycle)
            ((LifeCycle)_strategy).stop();
        threads.stop();
    }
    
    public static abstract class TestProducer implements Producer
    {
        @Override
        public String toString()
        {
            return "TestProducer";
        }
    }
    
    @Test
    public void idleTest() throws Exception
    {
        AtomicInteger count = new AtomicInteger(0);
        Producer producer = new TestProducer()
        {
            @Override
            public Runnable produce()
            {
                count.incrementAndGet();
                return null;
            }
        };
        
        newExecutionStrategy(producer,threads);
        _strategy.produce();
        assertThat(count.get(),greaterThan(0));
    }
    
    @Test
    public void simpleTest() throws Exception
    {
        final int TASKS = 3*threads.getMaxThreads();
        final CountDownLatch latch = new CountDownLatch(TASKS);
        Producer producer = new TestProducer()
        {
            int tasks = TASKS;
            @Override
            public Runnable produce()
            {
                if (tasks-->0)
                {
                    return new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            latch.countDown();
                        }
                    };
                }

                return null;
            }
        };
        
        newExecutionStrategy(producer,threads);
        
        Invocable.invokeNonBlocking(()->
        {
            for (int p=0; latch.getCount()>0 && p<TASKS; p++)
                _strategy.produce();
        }); 
        
        assertTrue(latch.await(10,TimeUnit.SECONDS));
    }
    

    @Test
    public void blockingProducerTest() throws Exception
    {
        final int TASKS = 3*threads.getMaxThreads();
        final BlockingQueue<CountDownLatch> q = new ArrayBlockingQueue<>(500);
        
        Producer producer = new TestProducer()
        {
            int tasks=TASKS;
            @Override
            public Runnable produce()
            {
                if (tasks-->0)
                {
                    try
                    {
                        final CountDownLatch latch = q.poll(10,TimeUnit.SECONDS);

                        if (latch!=null)
                        {
                            return new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    latch.countDown();
                                }
                            };
                        }
                    }
                    catch(InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }

                return null;
            }
        };
        
        newExecutionStrategy(producer,threads);

        final CountDownLatch latch = new CountDownLatch(TASKS);
        threads.execute(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    for (int t=TASKS;t-->0;)
                    {
                        Thread.sleep(20);
                        q.offer(latch);
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        });


        Invocable.invokeNonBlocking(()->
        {
            for (int p=0; latch.getCount()>0 && p<TASKS; p++)
                _strategy.produce();
        }); 
        
        assertTrue(latch.await(10,TimeUnit.SECONDS));
        
    }
    
    
    
}
