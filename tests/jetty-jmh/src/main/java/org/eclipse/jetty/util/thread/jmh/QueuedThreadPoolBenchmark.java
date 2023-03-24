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

package org.eclipse.jetty.util.thread.jmh;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class QueuedThreadPoolBenchmark
{
    QueuedThreadPool pool;
    private CountDownLatch[] latches;

    @Setup // (Level.Iteration)
    public void buildPool()
    {
        pool = new QueuedThreadPool(200, 200);
        pool.setReservedThreads(0);
        LifeCycle.start(pool);
        latches = new CountDownLatch[50];
        for (int i = 0; i < latches.length; i++)
        {
            latches[i] = new CountDownLatch(1);
        }
    }

    @TearDown // (Level.Iteration)
    public void shutdownPool()
    {
        System.err.println(pool);
        LifeCycle.stop(pool);
        pool = null;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(8)
    public void test() throws Exception
    {
        for (CountDownLatch latch : latches)
        {
            pool.execute(latch::countDown);
        }
        for (CountDownLatch latch : latches)
        {
            latch.await();
        }
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(QueuedThreadPoolBenchmark.class.getSimpleName())
            .forks(1)
            // .addProfiler(CompilerProfiler.class)
            // .addProfiler(LinuxPerfProfiler.class)
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class, "hotThreshold=0.05")
            .build();

        new Runner(opt).run();
    }
}
