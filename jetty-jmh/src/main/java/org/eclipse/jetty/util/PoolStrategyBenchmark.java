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

import java.util.concurrent.atomic.LongAdder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class PoolStrategyBenchmark
{
    private PoolWithStrategy<String> poolws;
    private Pool<String> pool;

    @Param({
        "linear",
        "Pool.Linear",
        "threadlocal+linear",
        "Pool.Thread",
        "random+linear",
        "Pool.Random",
        "roundrobin+linear",
        "Pool.RoundRobin",
        "threadid+linear",
        "Pool.ThreadId",
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

        switch (POOL_TYPE)
        {
            case "linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.LinearSearchStrategy<>());
                break;
            case "random+linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.CompositeStrategy<>(new PoolWithStrategy.RandomStrategy<>(), new PoolWithStrategy.LinearSearchStrategy<>()));
                break;
            case "threadlocal+linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.CompositeStrategy<>(new PoolWithStrategy.ThreadLocalStrategy<>(), new PoolWithStrategy.LinearSearchStrategy<>()));
                break;
            case "threadid+linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.CompositeStrategy<>(new PoolWithStrategy.ThreadIdStrategy<>(), new PoolWithStrategy.LinearSearchStrategy<>()));
                break;
            case "threadlocallist+linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.CompositeStrategy<>(new PoolWithStrategy.ThreadLocalListStrategy<>(2), new PoolWithStrategy.LinearSearchStrategy<>()));
                break;
            case "random-iteration":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.RandomIterationStrategy<>());
                break;
            case "threadlocal-iteration":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.ThreadLocalIteratorStrategy<>(false));
                break;
            case "threadlocal-iteration-roundrobin":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.ThreadLocalIteratorStrategy<>(true));
                break;
            case "retry+roundrobin":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.RetryStategy<>(new PoolWithStrategy.RoundRobinStrategy<>()));
                break;
            case "roundrobin+linear":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.CompositeStrategy<>(new PoolWithStrategy.RoundRobinStrategy<>(), new PoolWithStrategy.LinearSearchStrategy<>()));
                break;
            case "roundrobin-iteration":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.RoundRobinIterationStrategy<>());
                break;
            case "lru":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.LeastRecentlyUsedStrategy<>());
                break;
            case "OSTRTA(LINEAR)":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.OneStrategyToRuleThemAll<>(PoolWithStrategy.OneStrategyToRuleThemAll.Mode.LINEAR));
                break;
            case "OSTRTA(RANDOM)":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.OneStrategyToRuleThemAll<>(PoolWithStrategy.OneStrategyToRuleThemAll.Mode.RANDOM));
                break;
            case "OSTRTA(THREAD_LOCAL)":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.OneStrategyToRuleThemAll<>(PoolWithStrategy.OneStrategyToRuleThemAll.Mode.THREAD_LOCAL));
                break;
            case "OSTRTA(ROUND_ROBIN)":
                poolws = new PoolWithStrategy<>(SIZE, new PoolWithStrategy.OneStrategyToRuleThemAll<>(PoolWithStrategy.OneStrategyToRuleThemAll.Mode.ROUND_ROBIN));
                break;
            case "Pool.Linear" :
                pool = new Pool<>(Pool.Mode.LINEAR, SIZE);
                break;
            case "Pool.Random" :
                pool = new Pool<>(Pool.Mode.RANDOM, SIZE);
                break;
            case "Pool.ThreadId" :
                pool = new Pool<>(Pool.Mode.THREAD_ID, SIZE, false);
                break;
            case "Pool.Thread" :
                pool = new Pool<>(Pool.Mode.LINEAR, SIZE, true);
                break;
            case "Pool.RoundRobin" :
                pool = new Pool<>(Pool.Mode.ROUND_ROBIN, SIZE);
                break;

            default:
                throw new IllegalStateException();
        }

        for (int i = 0; i < SIZE; i++)
        {
            if (poolws != null)
                poolws.reserve(1).enable(Integer.toString(i), false);
            if (pool != null)
                pool.reserve(1).enable(Integer.toString(i), false);
        }
    }

    @TearDown
    public void tearDown()
    {
        System.err.printf("%nMISSES = %d (%d%%)%n", misses.longValue(), 100 * misses.longValue() / (hits.longValue() + misses.longValue()));
        System.err.printf("AVERAGE = %d%n", total.longValue() / hits.longValue());
        if (poolws != null)
            poolws.close();
        poolws = null;
        if (pool != null)
            pool.close();
        pool = null;
    }

    @Benchmark
    public void testAcquireReleasePoolWithStrategy()
    {
        if (poolws == null)
        {
            // Now really benchmark the strategy we are interested in
            Pool<String>.Entry entry = pool.acquire();
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
        else
        {
            // Now really benchmark the strategy we are interested in
            PoolWithStrategy<String>.Entry entry = poolws.acquire();
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
            // .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
