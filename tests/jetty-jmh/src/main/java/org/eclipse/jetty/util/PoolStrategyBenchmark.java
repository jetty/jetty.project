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
    private Pool<String> pool;

    @Param({
        "First",
        "Random",
        "RoundRobin",
        "ThreadId"
    })
    public static String POOL_TYPE;

    @Param({
        "false",
        "true"
    })
    public static boolean CACHE;

    @Param({
        "4",
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

        pool = switch (POOL_TYPE)
        {
            case "First" -> new ConcurrentPool<>(ConcurrentPool.StrategyType.FIRST, SIZE, CACHE);
            case "Random" -> new ConcurrentPool<>(ConcurrentPool.StrategyType.RANDOM, SIZE, CACHE);
            case "ThreadId" -> new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, SIZE, CACHE);
            case "RoundRobin" -> new ConcurrentPool<>(ConcurrentPool.StrategyType.ROUND_ROBIN, SIZE, CACHE);
            default -> throw new IllegalStateException();
        };

        for (int i = 0; i < SIZE; i++)
        {
            pool.reserve().enable(Integer.toString(i), false);
        }
    }

    @TearDown
    public void tearDown()
    {
        System.err.printf("%nMISSES = %d (%d%%)%n", misses.longValue(), 100 * misses.longValue() / (hits.longValue() + misses.longValue()));
        System.err.printf("AVERAGE = %d%n", total.longValue() / hits.longValue());
        pool.terminate();
        pool = null;
    }

    @Benchmark
    public void testAcquireReleasePoolWithStrategy()
    {
        // Now really benchmark the strategy we are interested in
        Pool.Entry<String> entry = pool.acquire();
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
            .result("/tmp/poolStrategy-" + System.currentTimeMillis() + ".json")
            // .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
