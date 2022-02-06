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

package org.eclipse.jetty.util.thread.jmh;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;
import org.eclipse.jetty.util.thread.TryExecutor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class ReservedThreadPoolBenchmark
{
    public enum Type
    {
        RTP
    }

    @Param({"RTP"})
    Type type;

    @Param({"0", "8", "32"})
    int size;

    QueuedThreadPool qtp;
    TryExecutor pool;

    @Setup // (Level.Iteration)
    public void buildPool()
    {
        qtp = new QueuedThreadPool();
        switch (type)
        {
            case RTP:
            {
                ReservedThreadExecutor pool = new ReservedThreadExecutor(qtp, size);
                pool.setIdleTimeout(1, TimeUnit.SECONDS);
                this.pool = pool;
                break;
            }
        }
        LifeCycle.start(qtp);
        LifeCycle.start(pool);
    }

    @TearDown // (Level.Iteration)
    public void shutdownPool()
    {
        LifeCycle.stop(pool);
        LifeCycle.stop(qtp);
        pool = null;
        qtp = null;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void testFew() throws Exception
    {
        doJob();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(8)
    public void testSome() throws Exception
    {
        doJob();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(200)
    public void testMany() throws Exception
    {
        doJob();
    }

    void doJob() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = () ->
        {
            Blackhole.consumeCPU(1);
            Thread.yield();
            Blackhole.consumeCPU(1);
            latch.countDown();
            Blackhole.consumeCPU(1);
        };
        if (!pool.tryExecute(task))
            qtp.execute(task);
        latch.await();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ReservedThreadPoolBenchmark.class.getSimpleName())
            .forks(1)
            // .threads(400)
            // .syncIterations(true) // Don't start all threads at same time
            // .addProfiler(CompilerProfiler.class)
            // .addProfiler(LinuxPerfProfiler.class)
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class)
            // .resultFormat(ResultFormatType.CSV)
            .build();

        new Runner(opt).run();
    }
}
