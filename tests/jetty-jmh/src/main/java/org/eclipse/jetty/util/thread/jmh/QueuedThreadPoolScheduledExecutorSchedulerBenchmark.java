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

import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
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
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class QueuedThreadPoolScheduledExecutorSchedulerBenchmark
{
    @Param({
        "BAQ",
        "ABQ",
    })
    public static String QUEUE_TYPE;

    QueuedThreadPool pool;
    ScheduledExecutorScheduler scheduler;

    @Setup(Level.Iteration)
    public void buildPool()
    {
        BlockingQueue<Runnable> q = switch (QUEUE_TYPE)
        {
            case "BAQ" -> new BlockingArrayQueue<>(1024 * 1204, 4 * 1024);
            case "ABQ" -> new ArrayBlockingQueue<>(16 * 1024 * 1204);
            default -> throw new IllegalArgumentException();
        };
        pool = new QueuedThreadPool(200, 200, q);
        pool.setStopTimeout(30000);
        pool.setReservedThreads(0);
        LifeCycle.start(pool);

        scheduler = new ScheduledExecutorScheduler();
        LifeCycle.start(scheduler);
    }

    @TearDown(Level.Iteration)
    public void shutdownPool()
    {
        LifeCycle.stop(pool);
        LifeCycle.stop(scheduler);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(8)
    public void test()
    {
        pool.execute((CloseableRunnable)() ->
        {
            Scheduler.Task task = scheduler.schedule(() -> {}, 1, TimeUnit.SECONDS);
            task.cancel();
        });
    }

    public static void main(String[] args) throws RunnerException
    {
        // String asyncProfilerPath = "/home/lorban/work/tools/async-profiler/4.0/lib/libasyncProfiler.so";
        Options opt = new OptionsBuilder()
            .include(QueuedThreadPoolScheduledExecutorSchedulerBenchmark.class.getSimpleName())
            .forks(1)
            // .addProfiler(CompilerProfiler.class)
            // .addProfiler(LinuxPerfProfiler.class)
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class, "hotThreshold=0.05")
            // .addProfiler(AsyncProfiler.class, "dir=/tmp/QTP;output=jfr;event=cpu;libPath=" + asyncProfilerPath)
            .build();

        new Runner(opt).run();
    }

    private interface CloseableRunnable extends Closeable, Runnable
    {
        @Override
        default void close()
        {
        }
    }
}
