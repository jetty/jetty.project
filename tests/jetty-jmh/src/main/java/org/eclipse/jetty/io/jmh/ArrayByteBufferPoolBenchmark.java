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

package org.eclipse.jetty.io.jmh;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.ThreadIdCache;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
public class ArrayByteBufferPoolBenchmark
{
    public static void main(String[] args) throws RunnerException
    {
        String asyncProfilerPath = "/home/simon/programs/async-profiler/lib/libasyncProfiler.so";
        Options opt = new OptionsBuilder()
            .include(ArrayByteBufferPoolBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .warmupTime(TimeValue.milliseconds(500))
            .measurementIterations(10)
            .measurementTime(TimeValue.milliseconds(500))
            .addProfiler(AsyncProfiler.class, "dir=/tmp;output=flamegraph;event=cpu;interval=500000;libPath=" + asyncProfilerPath)
            .forks(1)
            .threads(32)
            .build();
        new Runner(opt).run();
    }

    @Param("0")
    int minCapacity;
    @Param("65536")
    int maxCapacity;
    @Param("4096")
    int factor;
    @Param("-1")
    int maxBucketSize;
    @Param({"0", "1048576"})
    long maxMemory;
    @Param({"true"})
    boolean statisticsEnabled;

    ArrayByteBufferPool pool;

    @Setup
    public void prepare()
    {
        pool = new ArrayByteBufferPool(minCapacity, factor, maxCapacity, maxBucketSize, maxMemory, maxMemory);
        pool.setStatisticsEnabled(statisticsEnabled);
    }

    @TearDown
    public void dispose()
    {
        System.out.println(pool.dump());
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void inputFixedCapacityOutputRandomCapacity()
    {
        // Simulate a read from the network.
        RetainableByteBuffer input = pool.acquire(61440, true);

        // Simulate a write of random size from the application.
        int capacity = ThreadIdCache.Random.instance().nextInt(minCapacity, maxCapacity);
        RetainableByteBuffer output = pool.acquire(capacity, true);

        output.release();
        input.release();
    }

    int iterations;

    @Setup(Level.Iteration)
    public void migrate()
    {
        ++iterations;
        if (iterations == 15)
            System.out.println(pool.dump());
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void inputFixedCapacityOutputRandomCapacityMigrating()
    {
        // Simulate a read from the network.
        RetainableByteBuffer input = pool.acquire(8192, true);

        // Simulate a write of random size from the application.
        // Simulate a change in buffer sizes after half of the iterations.
        int capacity;
        if (iterations <= 15)
            capacity = ThreadIdCache.Random.instance().nextInt(minCapacity, maxCapacity / 2);
        else
            capacity = ThreadIdCache.Random.instance().nextInt(maxCapacity / 2, maxCapacity);
        RetainableByteBuffer output = pool.acquire(capacity, true);

        output.release();
        input.release();
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void fastPathAcquireRelease()
    {
        RetainableByteBuffer buffer = pool.acquire(65535, true);
        buffer.release();
    }
}
