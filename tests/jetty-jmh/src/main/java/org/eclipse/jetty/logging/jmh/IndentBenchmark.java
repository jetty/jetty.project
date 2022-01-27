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

package org.eclipse.jetty.logging.jmh;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class IndentBenchmark
{
    private static final int SMALL = 13;
    private static final int LARGE = 43;
    private String bigstring = "                                                                                    ";

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testStringSubStringSmall(Blackhole blackhole)
    {
        blackhole.consume(bigstring.substring(0, SMALL));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testStringSubStringLarge(Blackhole blackhole)
    {
        blackhole.consume(bigstring.substring(0, LARGE));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testStringRepeatSmall(Blackhole blackhole)
    {
        blackhole.consume(" ".repeat(SMALL));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testStringRepeatLarge(Blackhole blackhole)
    {
        blackhole.consume(" ".repeat(LARGE));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(IndentBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
