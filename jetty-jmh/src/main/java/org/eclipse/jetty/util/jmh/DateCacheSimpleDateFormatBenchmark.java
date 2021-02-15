//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.jmh;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

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
public class DateCacheSimpleDateFormatBenchmark
{

    DateCacheSimpleDateFormat dateCache = new DateCacheSimpleDateFormat();
    long timestamp = Instant.now().toEpochMilli();

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testDateCacheTimestamp()
    {
        dateCache.format(timestamp);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testDateCacheNow()
    {
        dateCache.format(new Date());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testDateCacheFormatNow()
    {
        dateCache.formatNow(System.currentTimeMillis());
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(DateCacheSimpleDateFormatBenchmark.class.getSimpleName())
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
