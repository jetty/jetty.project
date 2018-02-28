//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread.jmh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class ThreadPoolBenchmark
{
    public enum Type
    {
        QTP, ETP;
    }

    @Param({ "QTP", "ETP"})
    Type type;

    @Param({ "50"})
    int tasks;
    
    @Param({ "200", "2000"})
    int size;

    ThreadPool pool;

    @Setup // (Level.Iteration)
    public void buildPool()
    {
        switch(type)
        {
            case QTP:
                pool = new QueuedThreadPool(size);
                break;
                
            case ETP:
                pool = new ExecutorThreadPool(size);
                break;
        }
        LifeCycle.start(pool);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testPool()
    {
        doWork().join();
    }

    @TearDown // (Level.Iteration)
    public void shutdownPool()
    {
        LifeCycle.stop(pool);
        pool = null;
    }

    CompletableFuture<Void> doWork()
    {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++)
        {
            final CompletableFuture<Void> f = new CompletableFuture<Void>();
            futures.add(f);
            pool.execute(() -> {
                Blackhole.consumeCPU(64);
                f.complete(null);
            });
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public static void main(String[] args) throws RunnerException 
    {
        Options opt = new OptionsBuilder()
                .include(ThreadPoolBenchmark.class.getSimpleName())
                .warmupIterations(2)
                .measurementIterations(3)
                .forks(1)
                .threads(400)
                // .syncIterations(true) // Don't start all threads at same time
                .warmupTime(new TimeValue(10000,TimeUnit.MILLISECONDS))
                .measurementTime(new TimeValue(10000,TimeUnit.MILLISECONDS))
                // .addProfiler(CompilerProfiler.class)
                // .addProfiler(LinuxPerfProfiler.class)
                // .addProfiler(LinuxPerfNormProfiler.class)
                // .addProfiler(LinuxPerfAsmProfiler.class)
                // .resultFormat(ResultFormatType.CSV)
                .build();
        
        new Runner(opt).run();
    }
}