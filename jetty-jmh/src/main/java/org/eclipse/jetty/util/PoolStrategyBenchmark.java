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
        "Pool.Linear",
        "Pool.Random",
        "Pool.RoundRobin",
        "Pool.ThreadId",
    })
    public static String POOL_TYPE;

    @Param({
        "false",
        "true",
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

        switch (POOL_TYPE)
        {
            case "Pool.Linear" :
                pool = new Pool<>(Pool.StrategyType.FIRST, SIZE, CACHE);
                break;
            case "Pool.Random" :
                pool = new Pool<>(Pool.StrategyType.RANDOM, SIZE, CACHE);
                break;
            case "Pool.ThreadId" :
                pool = new Pool<>(Pool.StrategyType.THREAD_ID, SIZE, CACHE);
                break;
            case "Pool.RoundRobin" :
                pool = new Pool<>(Pool.StrategyType.ROUND_ROBIN, SIZE, CACHE);
                break;

            default:
                throw new IllegalStateException();
        }

        for (int i = 0; i < SIZE; i++)
        {
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
    public void testAcquireReleasePoolWithStrategy()
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
