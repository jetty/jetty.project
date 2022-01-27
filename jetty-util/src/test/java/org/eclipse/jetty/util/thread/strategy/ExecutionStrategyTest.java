//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.thread.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExecutionStrategyTest
{
    public static Stream<Arguments> strategies()
    {
        return Stream.of(
            ProduceExecuteConsume.class,
            ExecuteProduceConsume.class,
            AdaptiveExecutionStrategy.class
        ).map(Arguments::of);
    }

    QueuedThreadPool _threads = new QueuedThreadPool(20);
    List<ExecutionStrategy> strategies = new ArrayList<>();

    protected ExecutionStrategy newExecutionStrategy(Class<? extends ExecutionStrategy> strategyClass, Producer producer, Executor executor) throws Exception
    {
        ExecutionStrategy strategy = strategyClass.getDeclaredConstructor(Producer.class, Executor.class).newInstance(producer, executor);
        strategies.add(strategy);
        LifeCycle.start(strategy);
        return strategy;
    }

    @BeforeEach
    public void before() throws Exception
    {
        _threads.setDetailedDump(true);
        _threads.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        strategies.forEach((strategy) -> LifeCycle.stop(strategy));
        _threads.stop();
    }

    public abstract static class TestProducer implements Producer
    {
        @Override
        public String toString()
        {
            return "TestProducer";
        }
    }

    @ParameterizedTest
    @MethodSource("strategies")
    public void idleTest(Class<? extends ExecutionStrategy> strategyClass) throws Exception
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

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, _threads);
        strategy.produce();
        assertThat(count.get(), greaterThan(0));
    }

    @ParameterizedTest
    @MethodSource("strategies")
    public void simpleTest(Class<? extends ExecutionStrategy> strategyClass) throws Exception
    {
        final int TASKS = 3 * _threads.getMaxThreads();
        final CountDownLatch latch = new CountDownLatch(TASKS);
        Producer producer = new TestProducer()
        {
            int tasks = TASKS;

            @Override
            public Runnable produce()
            {
                if (tasks-- > 0)
                {
                    return () -> latch.countDown();
                }

                return null;
            }
        };

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, _threads);

        for (int p = 0; latch.getCount() > 0 && p < TASKS; p++)
        {
            strategy.produce();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
            () ->
            {
                // Dump state on failure
                return String.format("Timed out waiting for latch: %s%ntasks=%d latch=%d%n%s",
                    strategy, TASKS, latch.getCount(), _threads.dump());
            });
    }

    @ParameterizedTest
    @MethodSource("strategies")
    public void blockingProducerTest(Class<? extends ExecutionStrategy> strategyClass) throws Exception
    {
        final int TASKS = 3 * _threads.getMaxThreads();
        final BlockingQueue<CountDownLatch> q = new ArrayBlockingQueue<>(_threads.getMaxThreads());

        Producer producer = new TestProducer()
        {
            AtomicInteger tasks = new AtomicInteger(TASKS);

            @Override
            public Runnable produce()
            {
                final int id = tasks.decrementAndGet();

                if (id >= 0)
                {
                    while (_threads.isRunning())
                    {
                        try
                        {
                            final CountDownLatch latch = q.take();
                            return () -> latch.countDown();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }
                }

                return null;
            }
        };

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, _threads);
        strategy.dispatch();

        final CountDownLatch latch = new CountDownLatch(TASKS);
        _threads.execute(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    for (int t = TASKS; t-- > 0; )
                    {
                        Thread.sleep(20);
                        q.offer(latch);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        assertTrue(latch.await(30, TimeUnit.SECONDS),
            String.format("Timed out waiting for latch: %s%ntasks=%d latch=%d q=%d%n%s",
                strategy, TASKS, latch.getCount(), q.size(), _threads.dump()));
    }
}
