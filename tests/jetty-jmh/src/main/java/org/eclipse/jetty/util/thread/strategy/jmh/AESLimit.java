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

package org.eclipse.jetty.util.thread.strategy.jmh;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
@Fork(1)
@Threads(32)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class AESLimit
{
    private AdaptiveExecutionStrategy aes;
    private List<Histogram> histograms;
    private QueuedThreadPool qtp;

    @Setup(Level.Trial)
    public void setup() throws Exception
    {
        histograms = new CopyOnWriteArrayList<>();
        ThreadLocal<Histogram> histogramTl = ThreadLocal.withInitial(() ->
        {
            Histogram histogram = new Histogram(3);
            histograms.add(histogram);
            return histogram;
        });

        qtp = new QueuedThreadPool(4);
        qtp.setStopTimeout(10_000);
        aes = new AdaptiveExecutionStrategy(() ->
        {
            if (qtp.getQueueSize() > 100_000)
                throw new RuntimeException(Thread.currentThread().getName() + " made queue too large");
            return new Runnable()
            {
                final long before = System.nanoTime();

                @Override
                public void run()
                {
                    histogramTl.get().recordValue(NanoTime.now() - before);
                }
            };
        }, qtp);
        aes.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception
    {
        System.out.println("Waiting for " + qtp.getQueueSize() + " entries in job queue to be consumed...");
        aes.stop();

        System.out.println();
        Histogram combined = new Histogram(3);
        for (Histogram histogram : histograms)
        {
            combined.add(histogram);
        }
        combined.outputPercentileDistribution(System.out, 1000.0);
        System.out.println(aes.toString());
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void test()
    {
        aes.produce();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(AESLimit.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
