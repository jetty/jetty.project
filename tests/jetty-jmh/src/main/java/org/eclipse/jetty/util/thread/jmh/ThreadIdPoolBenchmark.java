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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.ThreadIdPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(200)
public class ThreadIdPoolBenchmark
{
    private final ThreadIdPool<String> threadIdPool = new ThreadIdPool<>(32);

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void test()
    {
        String s = threadIdPool.take();

        Blackhole.consumeCPU(100);

        threadIdPool.offer(s);
    }

    public static void main(String[] args) throws RunnerException
    {
        // Measure false-sharing with:
        //   perf c2c record -- java -jar target/benchmarks.jar ThreadIdPoolBenchmark
        // then print report with:
        //   perf c2c report --stdio
        Options opt = new OptionsBuilder()
            .include(ThreadIdPoolBenchmark.class.getSimpleName())
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
