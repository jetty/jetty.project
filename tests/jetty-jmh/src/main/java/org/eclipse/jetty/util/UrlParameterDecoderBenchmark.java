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

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;

@State(Scope.Benchmark)
@Threads(1)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class UrlParameterDecoderBenchmark
{
    private static final String SMALL_STRING = "param=aaa&other=foo";
    private static final int SMALL_LENGTH = SMALL_STRING.length();
    private static final byte[] SMALL_BYTES = SMALL_STRING.getBytes(UTF_8);
    private static final String LARGE_STRING = "text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit";
    private static final int LARGE_LENGTH = LARGE_STRING.length();
    private static final byte[] LARGE_BYTES = LARGE_STRING.getBytes(UTF_8);

    private UrlParameterDecoder decoder;
    private InputStream smallInputStream;
    private InputStream largeInputStream;
    private Reader smallReader;
    private Reader largeReader;

    @Setup(Level.Trial)
    public void setupTrial(Blackhole blackhole)
    {
        decoder = new UrlParameterDecoder(CharsetStringBuilder.forCharset(UTF_8), new BlackholeBiConsumer(blackhole));
    }

    @Setup(Level.Invocation)
    public void setupInvocation()
    {
        // Even when the benchmark method does not need these fields, the performance still is impacted by
        // the extra garbage those allocations leave behind them.
        smallInputStream = new ByteArrayInputStream(SMALL_BYTES);
        largeInputStream = new ByteArrayInputStream(LARGE_BYTES);

        smallReader = new StringReader(SMALL_STRING);
        largeReader = new StringReader(LARGE_STRING);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallString(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(SMALL_STRING, 0, SMALL_LENGTH));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testLargeString(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(LARGE_STRING, 0, LARGE_LENGTH));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallInputStream(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(smallInputStream, UTF_8));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testLargeInputStream(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(largeInputStream, UTF_8));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallReader(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(smallReader));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testLargeReader(Blackhole blackhole) throws Exception
    {
        blackhole.consume(decoder.parse(largeReader));
    }

    private static class BlackholeBiConsumer implements BiConsumer<String, String>
    {
        private final Blackhole blackhole;

        public BlackholeBiConsumer(Blackhole blackhole)
        {
            this.blackhole = blackhole;
        }

        @Override
        public void accept(String s, String s2)
        {
            blackhole.consume(s);
            blackhole.consume(s2);
        }
    }

    public static void main(String[] args) throws RunnerException
    {
        // String asyncProfilerPath = "/home/joakim/java/async-profiler-4.0-linux-x64/lib/libasyncProfiler.so";
        Options opt = new OptionsBuilder()
            .include(UrlParameterDecoderBenchmark.class.getSimpleName())
            // .addProfiler(AsyncProfiler.class, "dir=/home/joakim/tmp/urlparamdecoder;output=flamegraph;event=cpu;interval=500000;libPath=" + asyncProfilerPath)
            .forks(1)
            // .addProfiler(LinuxPerfNormProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
