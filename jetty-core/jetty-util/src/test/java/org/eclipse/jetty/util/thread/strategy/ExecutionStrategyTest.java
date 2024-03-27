//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
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
    public static Stream<Arguments> pooledStrategies()
    {
        return Stream.of(
            QueuedThreadPool.class,
            ExecutorThreadPool.class,
            VirtualThreads.getDefaultVirtualThreadsExecutor() == null ? null : VirtualThreadPool.class)
            .filter(Objects::nonNull)
            .flatMap(tp -> Stream.of(
            ProduceExecuteConsume.class,
            ExecuteProduceConsume.class,
            AdaptiveExecutionStrategy.class)
            .map(s -> Arguments.of(tp, s)));
    }

    List<Object> _lifeCycles = new ArrayList<>();

    protected ExecutionStrategy newExecutionStrategy(Class<? extends ExecutionStrategy> strategyClass, Producer producer, Executor executor) throws Exception
    {
        ExecutionStrategy strategy = strategyClass.getDeclaredConstructor(Producer.class, Executor.class).newInstance(producer, executor);
        _lifeCycles.add(executor);
        _lifeCycles.add(strategy);
        LifeCycle.start(strategy);
        return strategy;
    }

    @BeforeEach
    public void before() throws Exception
    {
    }

    @AfterEach
    public void after() throws Exception
    {
        _lifeCycles.forEach(LifeCycle::stop);
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
    @MethodSource("pooledStrategies")
    public void idleTest(Class<? extends ThreadPool> threadPoolClass, Class<? extends ExecutionStrategy> strategyClass) throws Exception
    {
        ThreadPool threadPool = threadPoolClass.getDeclaredConstructor().newInstance();
        LifeCycle.start(threadPool);
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

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, threadPool);
        strategy.produce();
        assertThat(count.get(), greaterThan(0));

        LifeCycle.stop(threadPool);
    }

    @ParameterizedTest
    @MethodSource("pooledStrategies")
    public void simpleTest(Class<? extends ThreadPool> threadPoolClass, Class<? extends ExecutionStrategy> strategyClass) throws Exception
    {
        ThreadPool threadPool = threadPoolClass.getDeclaredConstructor().newInstance();
        LifeCycle.start(threadPool);
        final int TASKS = 1000;
        final CountDownLatch latch = new CountDownLatch(TASKS);
        Producer producer = new TestProducer()
        {
            int tasks = TASKS;

            @Override
            public Runnable produce()
            {
                if (tasks-- > 0)
                {
                    return latch::countDown;
                }

                return null;
            }
        };

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, threadPool);

        for (int p = 0; latch.getCount() > 0 && p < TASKS; p++)
        {
            strategy.produce();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
            () ->
            {
                // Dump state on failure
                return String.format("Timed out waiting for latch: %s%ntasks=%d latch=%d%n%s",
                    strategy, TASKS, latch.getCount(), threadPool instanceof Dumpable dumpable ? dumpable.dump() : "");
            });

        LifeCycle.stop(threadPool);
    }

    @ParameterizedTest
    @MethodSource("pooledStrategies")
    public void blockingProducerTest(Class<? extends ThreadPool> threadPoolClass, Class<? extends ExecutionStrategy> strategyClass) throws Exception
    {
        ThreadPool threadPool = threadPoolClass.getDeclaredConstructor().newInstance();
        LifeCycle.start(threadPool);
        final int TASKS = 256;
        final BlockingQueue<CountDownLatch> q = new ArrayBlockingQueue<>(1024);

        Producer producer = new TestProducer()
        {
            AtomicInteger tasks = new AtomicInteger(TASKS);

            @Override
            public Runnable produce()
            {
                final int id = tasks.decrementAndGet();

                if (id >= 0)
                {
                    while (((LifeCycle)threadPool).isRunning())
                    {
                        try
                        {
                            final CountDownLatch latch = q.take();
                            return latch::countDown;
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

        ExecutionStrategy strategy = newExecutionStrategy(strategyClass, producer, threadPool);
        strategy.dispatch();

        final CountDownLatch latch = new CountDownLatch(TASKS);
        threadPool.execute(() ->
        {
            try
            {
                for (int t = TASKS; t-- > 0; )
                {
                    Thread.sleep(5);
                    q.offer(latch);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

        assertTrue(latch.await(30, TimeUnit.SECONDS),
            String.format("Timed out waiting for latch: %s%ntasks=%d latch=%d q=%d%n%s",
                strategy, TASKS, latch.getCount(), q.size(), threadPool instanceof Dumpable dumpable ? dumpable.dump() : ""));

        LifeCycle.stop(threadPool);
    }
}
