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
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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

import static java.nio.charset.StandardCharsets.UTF_8;

@State(Scope.Benchmark)
@Threads(1)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class UrlParameterDecoderBenchmark
{
    private static class BlackholeBiConsumer implements BiConsumer<String, String>
    {
        Blackhole blackhole;

        @Override
        public void accept(String s, String s2)
        {
            blackhole.consume(s);
            blackhole.consume(s2);
        }
    }

    private final BlackholeBiConsumer newFieldAdder = new BlackholeBiConsumer();
    private final UrlParameterDecoder decoder = new UrlParameterDecoder(CharsetStringBuilder.forCharset(UTF_8), newFieldAdder);

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallQuery(Blackhole blackhole) throws Exception
    {
        String input = "param=aaa&other=foo";
        newFieldAdder.blackhole = blackhole;
        blackhole.consume(decoder.parse(input));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testLargeQuery(Blackhole blackhole) throws Exception
    {
        String input = "text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit";
        newFieldAdder.blackhole = blackhole;
        blackhole.consume(decoder.parse(input));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallForm(Blackhole blackhole) throws Exception
    {
        String input = "param=aaa&other=foo";
        newFieldAdder.blackhole = blackhole;
        InputStream in = new ByteArrayInputStream(input.getBytes(UTF_8));
        blackhole.consume(decoder.parse(in, UTF_8));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testLargeForm(Blackhole blackhole) throws Exception
    {
        String input = "text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit";
        newFieldAdder.blackhole = blackhole;
        InputStream in = new ByteArrayInputStream(input.getBytes(UTF_8));
        blackhole.consume(decoder.parse(in, UTF_8));
    }

    private final InputStreamReader smallReader = new InputStreamReader(new ByteArrayInputStream("param=aaa&other=foo".getBytes(UTF_8)));

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void testSmallReader(Blackhole blackhole) throws Exception
    {
        newFieldAdder.blackhole = blackhole;
        blackhole.consume(decoder.parse(smallReader));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(UrlParameterDecoderBenchmark.class.getSimpleName())
            .forks(1)
//             .addProfiler(LinuxPerfNormProfiler.class)
//             .addProfiler(LinuxPerfAsmProfiler.class)
            .build();

        new Runner(opt).run();
    }

}
