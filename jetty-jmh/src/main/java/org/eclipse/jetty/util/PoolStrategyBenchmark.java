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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class PoolBenchmark
{
    private Pool<String> pool;
    private Queue<Pool<String>.Entry> queue = new ConcurrentLinkedQueue<>();

    @Param({
        "linear",
        "random+linear",
        "threadlocal+linear",
        "threadlocallist+linear",
        "random-iteration",
        "threadlocal-iteration",
        "threadlocal-iteration-roundrobin",
        "roundrobin",
        "roundrobin-iteration",
        "lru"
    })
    public static String POOL_TYPE;

    @Param({
        "10"
    })
    public static int SIZE;

    @Setup
    public void setUp() throws Exception
    {
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
            case "roundrobin":
                pool = new Pool<>(SIZE, new Pool.RoundRobinStrategy<>());
                break;
            case "roundrobin-iteration":
                pool = new Pool<>(SIZE, new Pool.RoundRobinIterationStrategy<>());
                break;
            case "lru":
                pool = new Pool<>(SIZE, new Pool.LeastRecentlyUsedStrategy<>());
                break;
            default:
                throw new IllegalStateException();
        }

        for (int i = 0; i < SIZE; i++)
            pool.reserve(1).enable(Integer.toString(i), false);
    }

    @TearDown
    public void tearDown()
    {
        pool.close();
        pool = null;
    }

    @Benchmark
    public void testAcquireReleasePool()
    {
        // acquire an entry
        Pool<String>.Entry entry = pool.acquire();
        if (entry == null || entry.isIdle())
            throw new IllegalStateException();

        // do some work
        Blackhole.consumeCPU(entry.getPooled().hashCode() % 200);

        // release the entry
        entry.release();
    }

    @Benchmark
    public void testReleaseAcquirePool()
    {
        // Release an entry, probably from another thread
        Pool<String>.Entry entry = queue.poll();
        if (entry != null)
        {
            entry.release();

            // Do some work
            Blackhole.consumeCPU(entry.getPooled().hashCode() % 100);
        }

        // Acquire another entry
        entry = pool.acquire();
        if (entry == null || entry.isIdle())
            throw new IllegalStateException();
        queue.add(entry);

        // Do some more work
        Blackhole.consumeCPU(entry.getPooled().hashCode() % 100);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(PoolBenchmark.class.getSimpleName())
            .warmupIterations(1)
            .measurementIterations(1)
            .forks(1)
            .threads(10)
            //.addProfiler(LinuxPerfProfiler.class)
            .build();

        new Runner(opt).run();
    }

}
