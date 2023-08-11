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

package org.eclipse.jetty.util.jmh;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.DateCache;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class DateCacheBenchmark
{
    TimeZone timeZone = TimeZone.getDefault();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DateCache.DEFAULT_FORMAT + " SSS").withZone(timeZone.toZoneId());
    DateCache dateCache = new DateCache(DateCache.DEFAULT_FORMAT + " SSS", null, timeZone, false);

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testDateTimeFormatter()
    {
        formatter.format(Instant.now());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testDateCache()
    {
        dateCache.format(System.currentTimeMillis());
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(DateCacheBenchmark.class.getSimpleName())
            .warmupIterations(2)
            .measurementIterations(3)
            .forks(1)
            .threads(400)
            // .syncIterations(true) // Don't start all threads at same time
            .warmupTime(new TimeValue(10000, TimeUnit.MILLISECONDS))
            .measurementTime(new TimeValue(10000, TimeUnit.MILLISECONDS))
            // .addProfiler(CompilerProfiler.class)
            // .addProfiler(LinuxPerfProfiler.class)
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class)
            // .resultFormat(ResultFormatType.CSV)
            .build();

        new Runner(opt).run();
    }
}
