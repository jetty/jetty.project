//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class PoolStrategyBenchmark
{
    private Pool<String> pool;
    private Pool<String> poolA;
    private Pool<String> poolB;
    private static final Queue<Pool<String>.Entry> queue = new ConcurrentLinkedQueue<>();

    @Param({
        "linear",
        "OSTRTA(LINEAR)",
        "threadlocal+linear",
        "OSTRTA(THREAD_LOCAL)",
        "random-iteration",
        "OSTRTA(RANDOM)",
        "roundrobin+linear",
        "OSTRTA(ROUND_ROBIN)",
    })
    public static String POOL_TYPE;

    @Param({
        "16"
    })
    public static int SIZE;

    private static final LongAdder misses = new LongAdder();
    private static final LongAdder hits = new LongAdder();
    private static final LongAdder total = new LongAdder();

    @Setup
    public void setUp() throws Exception
    {
        misses.reset();

        poolA = new Pool<>(SIZE, new Pool.Strategy<String>()
        {
            @Override
            public Pool<String>.Entry tryAcquire(List<Pool<String>.Entry> entries)
            {
                int i = (int)Thread.currentThread().getId() % entries.size();
                Pool<String>.Entry entry = entries.get(i);
                if (entry != null && entry.tryAcquire())
                    return entry;
                return null;
            }
        });

        poolB = new Pool<String>(SIZE, new Pool.Strategy<String>()
        {
            @Override
            public Pool<String>.Entry tryAcquire(List<Pool<String>.Entry> entries)
            {
                Pool<String>.Entry entry = entries.get(0);
                if (entry != null && entry.tryAcquire())
                    return entry;
                return null;
            }
        });

        switch (POOL_TYPE)
        {
            case "linear":
                pool = new Pool<>(SIZE, new Pool.LinearSearchStrategy<>());
                break;
            case "random+linear":
                pool = new Pool<>(SIZE, new Pool.CompositeStrategy<>(new Pool.RandomStrategy<>(), new Pool.LinearSearchStrategy<>()));
                break;
            case "threadlocal+linear":
                pool = new Pool<>(SIZE, new Pool.CompositeStrategy<>(new Pool.ThreadLocalStrategy<>(), new Pool.LinearSearchStrategy<>()));
                break;
            case "threadid+linear":
                pool = new Pool<>(SIZE, new Pool.CompositeStrategy<>(new Pool.ThreadIdStrategy<>(), new Pool.LinearSearchStrategy<>()));
                break;
            case "threadlocallist+linear":
                pool = new Pool<>(SIZE, new Pool.CompositeStrategy<>(new Pool.ThreadLocalListStrategy<>(2), new Pool.LinearSearchStrategy<>()));
                break;
            case "random-iteration":
                pool = new Pool<>(SIZE, new Pool.RandomIterationStrategy<>());
                break;
            case "threadlocal-iteration":
                pool = new Pool<>(SIZE, new Pool.ThreadLocalIteratorStrategy<>(false));
                break;
            case "threadlocal-iteration-roundrobin":
                pool = new Pool<>(SIZE, new Pool.ThreadLocalIteratorStrategy<>(true));
                break;
            case "retry+roundrobin":
                pool = new Pool<>(SIZE, new Pool.RetryStategy<>(new Pool.RoundRobinStrategy<>()));
                break;
            case "roundrobin+linear":
                pool = new Pool<>(SIZE, new Pool.CompositeStrategy<>(new Pool.RoundRobinStrategy<>(), new Pool.LinearSearchStrategy<>()));
                break;
            case "roundrobin-iteration":
                pool = new Pool<>(SIZE, new Pool.RoundRobinIterationStrategy<>());
                break;
            case "lru":
                pool = new Pool<>(SIZE, new Pool.LeastRecentlyUsedStrategy<>());
                break;
            case "OSTRTA(LINEAR)":
                pool = new Pool<>(SIZE, new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.LINEAR));
                break;
            case "OSTRTA(RANDOM)":
                pool = new Pool<>(SIZE, new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.RANDOM));
                break;
            case "OSTRTA(THREAD_LOCAL)":
                pool = new Pool<>(SIZE, new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.THREAD_LOCAL));
                break;
            case "OSTRTA(ROUND_ROBIN)":
                pool = new Pool<>(SIZE, new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.ROUND_ROBIN));
                break;

            default:
                throw new IllegalStateException();
        }

        for (int i = 0; i < SIZE; i++)
        {
            poolA.reserve(1).enable(Integer.toString(i), false);
            poolB.reserve(1).enable(Integer.toString(i), false);
            pool.reserve(1).enable(Integer.toString(i), false);
        }
    }

    @TearDown
    public void tearDown()
    {
        System.err.printf("%nMISSES = %d (%d%%)%n", misses.longValue(), 100 * misses.longValue() / (hits.longValue() + misses.longValue()));
        System.err.printf("AVERAGE = %d%n", total.longValue() / hits.longValue());
        pool.close();
        pool = null;
    }

    @Benchmark
    public void testAcquireReleasePool()
    {
        // force polymorphic pool optimization
        Pool<String>.Entry entry = poolA.acquire();
        if (entry != null)
            entry.release();
        entry = poolB.acquire();
        if (entry != null)
            entry.release();

        // Now really benchmark the strategy we are interested in
        entry = pool.acquire();
        if (entry == null || entry.isIdle())
        {
            misses.increment();
            Blackhole.consumeCPU(20);
            return;
        }
        // do some work
        hits.increment();
        total.add(Long.parseLong(entry.getPooled()));
        Blackhole.consumeCPU(entry.getPooled().hashCode() % 20);

        // release the entry
        entry.release();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(PoolStrategyBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .measurementIterations(3)
            .forks(1)
            .threads(8)
            .resultFormat(ResultFormatType.JSON)
            .result("poolStrategy-" + System.currentTimeMillis() + ".json")
            .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
