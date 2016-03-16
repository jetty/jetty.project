//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.toolchain.perf.PlatformMonitor;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;


@RunWith(value = Parameterized.class)
public class SchedulerTest
{
    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{
            {new TimerScheduler()},
            {new ScheduledExecutorScheduler()}/*,
            {new ConcurrentScheduler(0)},
            {new ConcurrentScheduler(1500)},
            {new ConcurrentScheduler(executor,1500)}*/
        };
        return Arrays.asList(data);
    }

    private Scheduler _scheduler;

    public SchedulerTest(Scheduler scheduler)
    {
        _scheduler=scheduler;
    }

    @Before
    public void before() throws Exception
    {
        _scheduler.start();
    }

    @After
    public void after() throws Exception
    {
        _scheduler.stop();
    }

    @Test
    public void testExecution() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        long expected=System.currentTimeMillis()+1000;
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },1000,TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        Assert.assertFalse(task.cancel());
        Assert.assertThat(executed.get(),Matchers.greaterThanOrEqualTo(expected));
        Assert.assertThat(expected-executed.get(),Matchers.lessThan(1000L));
    }

    @Test
    public void testTwoExecution() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        long expected=System.currentTimeMillis()+1000;
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },1000,TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        Assert.assertFalse(task.cancel());
        Assert.assertThat(executed.get(),Matchers.greaterThanOrEqualTo(expected));
        Assert.assertThat(expected-executed.get(),Matchers.lessThan(1000L));

        final AtomicLong executed1 = new AtomicLong();
        long expected1=System.currentTimeMillis()+1000;
        Scheduler.Task task1=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed1.set(System.currentTimeMillis());
            }
        },1000,TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        Assert.assertFalse(task1.cancel());
        Assert.assertThat(executed1.get(),Matchers.greaterThanOrEqualTo(expected1));
        Assert.assertThat(expected1-executed1.get(),Matchers.lessThan(1000L));
    }

    @Test
    public void testQuickCancel() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },2000,TimeUnit.MILLISECONDS);

        Thread.sleep(100);
        Assert.assertTrue(task.cancel());
        Thread.sleep(2500);
        Assert.assertEquals(0,executed.get());
    }

    @Test
    public void testLongCancel() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },2000,TimeUnit.MILLISECONDS);

        Thread.sleep(1600);
        Assert.assertTrue(task.cancel());
        Thread.sleep(1000);
        Assert.assertEquals(0,executed.get());
    }

    @Test
    public void testTaskThrowsException() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(TimerScheduler.class))
        {
            long delay = 500;
            _scheduler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    throw new RuntimeException("Thrown by testTaskThrowsException");
                }
            }, delay, TimeUnit.MILLISECONDS);

            TimeUnit.MILLISECONDS.sleep(2 * delay);

            // Check whether after a task throwing an exception, the scheduler is still working

            final CountDownLatch latch = new CountDownLatch(1);
            _scheduler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    latch.countDown();
                }
            }, delay, TimeUnit.MILLISECONDS);

            Assert.assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    @Slow
    @Ignore
    public void testManySchedulesAndCancels() throws Exception
    {
        schedule(100,5000,3800,200);
    }
    
    @Test
    public void testFewSchedulesAndCancels() throws Exception
    {
        schedule(10,500,380,20);
    }

    @Test
    @Slow
    @Ignore
    public void testBenchmark() throws Exception
    {
        schedule(2000,10000,2000,50);
        PlatformMonitor benchmark = new PlatformMonitor();
        PlatformMonitor.Start start = benchmark.start();
        System.err.println(start);
        System.err.println(_scheduler);
        schedule(2000,30000,2000,50);
        PlatformMonitor.Stop stop = benchmark.stop();
        System.err.println(stop);
    }

    private void schedule(int threads,final int duration, final int delay, final int interval) throws Exception
    {
        Thread[] test = new Thread[threads];

        final AtomicInteger schedules = new AtomicInteger();
        final SampleStatistic executions = new SampleStatistic();
        final SampleStatistic cancellations = new SampleStatistic();

        for (int i=test.length;i-->0;)
        {
            test[i]=new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Random random = new Random();
                        long now = System.currentTimeMillis();
                        long start=now;
                        long end=start+duration;
                        boolean last=false;
                        while (!last)
                        {
                            final long expected=now+delay;
                            int cancel=random.nextInt(interval);
                            final boolean expected_to_execute;

                            last=now+2*interval>end;
                            if (cancel==0 || last)
                            {
                                expected_to_execute=true;
                                cancel=delay+1000;
                            }
                            else
                                expected_to_execute=false;

                            schedules.incrementAndGet();
                            Scheduler.Task task=_scheduler.schedule(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    long lateness=System.currentTimeMillis()-expected;
                                    if (expected_to_execute)
                                        executions.set(lateness);
                                    else
                                        executions.set(6666);

                                }
                            },delay,TimeUnit.MILLISECONDS);

                            Thread.sleep(cancel);
                            now = System.currentTimeMillis();
                            if (task.cancel())
                            {
                                long lateness=now-expected;
                                if (expected_to_execute)
                                    cancellations.set(lateness);
                                else
                                    cancellations.set(0);
                            }
                            else
                            {
                                if (!expected_to_execute)
                                {
                                    cancellations.set(9999);
                                }
                            }

                            Thread.yield();
                        }
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
            };
        }

        for (Thread thread : test)
            thread.start();

        for (Thread thread : test)
            thread.join();

        // there were some executions and cancellations
        Assert.assertThat(executions.getCount(),Matchers.greaterThan(0L));
        Assert.assertThat(cancellations.getCount(),Matchers.greaterThan(0L));

        // All executed or cancelled
        // Not that SimpleScheduler can execute and cancel an event!
        Assert.assertThat(0L+schedules.get(),Matchers.lessThanOrEqualTo(executions.getCount()+cancellations.getCount()));

        // No really late executions
        Assert.assertThat(executions.getMax(),Matchers.lessThan(500L));

        // Executions on average are close to the expected time
        Assert.assertThat(executions.getMean(),Matchers.lessThan(500.0));

        // No cancellations long after expected executions
        Assert.assertThat(cancellations.getMax(),Matchers.lessThan(500L));
    }
}
