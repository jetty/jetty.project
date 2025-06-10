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
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
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
    private List<Histogram> histograms;
    private List<AdaptiveExecutionStrategy> strategies;
    private List<QueuedThreadPool> threadPools;
    private ThreadLocal<AdaptiveExecutionStrategy> aesTl;

    @Setup(Level.Trial)
    public void setup() throws Exception
    {
        threadPools = new CopyOnWriteArrayList<>();
        histograms = new CopyOnWriteArrayList<>();
        strategies = new CopyOnWriteArrayList<>();
        aesTl = ThreadLocal.withInitial(() ->
        {
            QueuedThreadPool qtp = new QueuedThreadPool(2);
            qtp.setStopTimeout(10_000);
            LifeCycle.start(qtp);
            threadPools.add(qtp);

            Histogram histogram = new ConcurrentHistogram(new Histogram(3));
            histograms.add(histogram);

            AdaptiveExecutionStrategy aes = new AdaptiveExecutionStrategy(new ExecutionStrategy.Producer()
            {
                boolean produceNull = false;
                @Override
                public Runnable produce()
                {
                    if (produceNull)
                    {
                        produceNull = false;
                        return null;
                    }
                    produceNull = true;

                    return new Runnable()
                    {
                        final long before = System.nanoTime();

                        @Override
                        public void run()
                        {
                            histogram.recordValue(NanoTime.now() - before);
                        }
                    };
                }
            }, qtp);
            LifeCycle.start(aes);
            strategies.add(aes);

            return aes;
        });
    }

    @TearDown(Level.Trial)
    public void tearDown()
    {
        strategies.forEach(strategy -> LifeCycle.stop(strategy));
        threadPools.forEach(threadPool -> LifeCycle.stop(threadPool));

        System.out.println();
        Histogram combined = new Histogram(3);
        for (Histogram histogram : histograms)
        {
            combined.add(histogram);
        }
        combined.outputPercentileDistribution(System.out, 1000.0);
        strategies.forEach(System.out::println);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void test()
    {
        aesTl.get().produce();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(AESLimit.class.getSimpleName())
//             .addProfiler(AsyncProfiler.class, "dir=/tmp/AESLimit;output=flamegraph;event=cpu;interval=500000;libPath=/home/lorban/work/tools/async-profiler/4.0/lib/libasyncProfiler.so")
            // .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
