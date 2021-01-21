//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SchedulerTest
{
    public static Stream<Class<? extends Scheduler>> schedulerProvider()
    {
        return Stream.of(
            TimerScheduler.class,
            ScheduledExecutorScheduler.class
        );
    }

    private List<Scheduler> schedulers = new ArrayList<>();

    public Scheduler start(Class<? extends Scheduler> impl) throws Exception
    {
        System.gc();
        Scheduler scheduler = impl.getDeclaredConstructor().newInstance();
        scheduler.start();
        schedulers.add(scheduler);
        assertThat("Scheduler is started", scheduler.isStarted(), is(true));
        return scheduler;
    }

    @AfterEach
    public void after()
    {
        schedulers.forEach((scheduler) ->
        {
            try
            {
                scheduler.stop();
            }
            catch (Exception ignore)
            {
                // no op
            }
        });
    }

    @ParameterizedTest
    @MethodSource("schedulerProvider")
    public void testExecution(Class<? extends Scheduler> impl) throws Exception
    {
        Scheduler scheduler = start(impl);
        final AtomicLong executed = new AtomicLong();
        long expected = System.currentTimeMillis() + 1000;
        Scheduler.Task task = scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        }, 1000, TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        assertFalse(task.cancel());
        assertThat(executed.get(), Matchers.greaterThanOrEqualTo(expected));
        assertThat(expected - executed.get(), Matchers.lessThan(1000L));
    }

    @ParameterizedTest
    @MethodSource("schedulerProvider")
    public void testTwoExecution(Class<? extends Scheduler> impl) throws Exception
    {
        Scheduler scheduler = start(impl);
        final AtomicLong executed = new AtomicLong();
        long expected = System.currentTimeMillis() + 1000;
        Scheduler.Task task = scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        }, 1000, TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        assertFalse(task.cancel());
        assertThat(executed.get(), Matchers.greaterThanOrEqualTo(expected));
        assertThat(expected - executed.get(), Matchers.lessThan(1000L));

        final AtomicLong executed1 = new AtomicLong();
        long expected1 = System.currentTimeMillis() + 1000;
        Scheduler.Task task1 = scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed1.set(System.currentTimeMillis());
            }
        }, 1000, TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        assertFalse(task1.cancel());
        assertThat(executed1.get(), Matchers.greaterThanOrEqualTo(expected1));
        assertThat(expected1 - executed1.get(), Matchers.lessThan(1000L));
    }

    @ParameterizedTest
    @MethodSource("schedulerProvider")
    public void testQuickCancel(Class<? extends Scheduler> impl) throws Exception
    {
        Scheduler scheduler = start(impl);
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task = scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        }, 2000, TimeUnit.MILLISECONDS);

        Thread.sleep(100);
        assertTrue(task.cancel());
        Thread.sleep(2500);
        assertEquals(0, executed.get());
    }

    @ParameterizedTest
    @MethodSource("schedulerProvider")
    public void testLongCancel(Class<? extends Scheduler> impl) throws Exception
    {
        Scheduler scheduler = start(impl);
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task = scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        }, 2000, TimeUnit.MILLISECONDS);

        Thread.sleep(100);
        assertTrue(task.cancel());
        Thread.sleep(2500);
        assertEquals(0, executed.get());
    }

    @ParameterizedTest
    @MethodSource("schedulerProvider")
    public void testTaskThrowsException(Class<? extends Scheduler> impl) throws Exception
    {
        Scheduler scheduler = start(impl);
        try (StacklessLogging ignore = new StacklessLogging(TimerScheduler.class))
        {
            long delay = 500;
            scheduler.schedule(new Runnable()
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
            scheduler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    latch.countDown();
                }
            }, delay, TimeUnit.MILLISECONDS);

            assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        }
    }
}
