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

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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
@Warmup(iterations = 10, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class ReservedThreadPoolBenchmark
{
    public enum Type
    {
        RTE_EXCH, RTE_SEMA, RTE_Q
    }

    @Param({"RTE_EXCH", "RTE_SEMA", "RTE_Q"})
    Type type;

    @Param({"16"})
    int size;

    QueuedThreadPool qtp;
    TryExecutor pool;
    LongAdder jobs = new LongAdder();
    LongAdder complete = new LongAdder();
    LongAdder hit = new LongAdder();
    LongAdder miss = new LongAdder();

    @Setup // (Level.Iteration)
    public void buildPool()
    {
        qtp = new QueuedThreadPool();
        switch (type)
        {
            case RTE_EXCH:
            {
                ReservedThreadExecutorExchanger pool = new ReservedThreadExecutorExchanger(qtp, size);
                pool.setIdleTimeout(5, TimeUnit.SECONDS);
                this.pool = pool;
                break;
            }
            case RTE_Q:
            {
                ReservedThreadExecutorSyncQueue pool = new ReservedThreadExecutorSyncQueue(qtp, size);
                pool.setIdleTimeout(5, TimeUnit.SECONDS);
                this.pool = pool;
                break;
            }
            case RTE_SEMA:
            {
                ReservedThreadExecutorSemaphore pool = new ReservedThreadExecutorSemaphore(qtp, size);
                pool.setIdleTimeout(5, TimeUnit.SECONDS);
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
        System.err.println("\nShutdown ...");
        long startSpin = System.nanoTime();
        while (complete.longValue() < jobs.longValue())
        {
            if (NanoTime.secondsSince(startSpin) > 5)
            {
                System.err.printf("FAILED %d < %d\n".formatted(complete.longValue(), jobs.longValue()));
                break;
            }
            Thread.onSpinWait();
        }
        System.err.println("Stopping ...");
        LifeCycle.stop(pool);
        LifeCycle.stop(qtp);
        pool = null;
        qtp = null;
        System.err.println("Stopped");
        long hits = hit.sum();
        System.err.printf(Locale.ROOT, "hit:miss = %.1f%% (%d:%d)", 100.0D * hits / (hits + miss.sum()), hits, miss.sum());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void test001Threads() throws Exception
    {
        doJob();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(32)
    public void test032Threads() throws Exception
    {
        doJob();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(200)
    public void test200Threads() throws Exception
    {
        doJob();
    }

    void doJob()
    {
        jobs.increment();
        Runnable task = () ->
        {
            Blackhole.consumeCPU(2);
            complete.increment();
        };
        if (pool.tryExecute(task))
        {
            hit.increment();
        }
        else
        {
            miss.increment();
            qtp.execute(task);
        }
        // We don't wait for the job to complete here, as we want to measure the speed of dispatch, not execution latency
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
