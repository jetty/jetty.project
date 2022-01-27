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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Warmup(iterations = 8, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class ThreadPoolBenchmark
{
    public enum Type
    {
        QTP, ETP, LQTP, LETP, AQTP, AETP;
    }

    @Param({"QTP", "ETP" /*, "LQTP", "LETP", "AQTP", "AETP" */})
    Type type;

    @Param({"200"})
    int size;

    ThreadPool pool;

    @Setup // (Level.Iteration)
    public void buildPool()
    {
        switch (type)
        {
            case QTP:
            {
                QueuedThreadPool qtp = new QueuedThreadPool(size, size, new BlockingArrayQueue<>(32768, 32768));
                qtp.setReservedThreads(0);
                pool = qtp;
                break;
            }

            case ETP:
                pool = new ExecutorThreadPool(size, size, new BlockingArrayQueue<>(32768, 32768));
                break;

            case LQTP:
            {
                QueuedThreadPool qtp = new QueuedThreadPool(size, size, new LinkedBlockingQueue<>());
                qtp.setReservedThreads(0);
                pool = qtp;
                break;
            }

            case LETP:
                pool = new ExecutorThreadPool(size, size, new LinkedBlockingQueue<>());
                break;

            case AQTP:
            {
                QueuedThreadPool qtp = new QueuedThreadPool(size, size, new ArrayBlockingQueue<>(32768));
                qtp.setReservedThreads(0);
                pool = qtp;
                break;
            }

            case AETP:
                pool = new ExecutorThreadPool(size, size, new ArrayBlockingQueue<>(32768));
                break;

            default:
                throw new IllegalStateException();
        }
        LifeCycle.start(pool);
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
    @Threads(4)
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

    @TearDown // (Level.Iteration)
    public void shutdownPool()
    {
        LifeCycle.stop(pool);
        pool = null;
    }

    void doJob() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        pool.execute(latch::countDown);
        latch.await();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ThreadPoolBenchmark.class.getSimpleName())
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
