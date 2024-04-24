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

package org.eclipse.jetty.http.jmh;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpDateTime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class HttpDateTimeParseBenchmark
{
    String[] expires;
    int size;

    @Setup
    public void prepare()
    {
        size = 40;
        expires = new String[size];

        long startTime = ZonedDateTime.parse("Mon, 01 Jan 1900 01:00:00 GMT", DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        long endTime = ZonedDateTime.parse("Fri, 31 Dec 2100 23:59:59 GMT", DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        ZoneId zoneGMT = ZoneId.of("GMT");

        for (int i = 0; i < size; i++)
        {
            long randomTime = random.nextLong(startTime, endTime);
            expires[i] = Instant.ofEpochSecond(randomTime).atZone(zoneGMT).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testParseDateTimeOld()
    {
        int entry = ThreadLocalRandom.current().nextInt(size);
        return DateParser.parseDate(expires[entry]);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public ZonedDateTime testParseNew()
    {
        int entry = ThreadLocalRandom.current().nextInt(size);
        return HttpDateTime.parse(expires[entry]);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(HttpDateTimeParseBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .measurementIterations(10)
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
    }
}


