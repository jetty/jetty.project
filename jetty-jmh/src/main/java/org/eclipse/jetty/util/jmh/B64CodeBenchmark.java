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

import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.B64Code;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class B64CodeBenchmark
{
    @Param({"200", "2000", "20000", "200000"})
    int size;

    Base64.Encoder javaEncoder;
    Base64.Decoder javaDecoder;
    byte[] rawBuffer;
    String rawEncodedBuffer;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception
    {
        rawBuffer = new byte[size];
        Random random = new Random();
        random.setSeed(8080);
        random.nextBytes(rawBuffer);

        javaEncoder = Base64.getEncoder();
        javaDecoder = Base64.getDecoder();

        // prepare encoded buffer for Decode benchmarks
        rawEncodedBuffer = Base64.getEncoder().encodeToString(rawBuffer);
    }

    @TearDown(Level.Trial)
    public static void stopTrial() throws Exception
    {
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public char[] testJettyEncode() throws Exception
    {
        return B64Code.encode(rawBuffer);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public byte[] testJettyDecode() throws Exception
    {
        return B64Code.decode(rawEncodedBuffer);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public byte[] testJavaEncode()
    {
        return javaEncoder.encode(rawBuffer);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public byte[] testJavaDecode()
    {
        return javaDecoder.decode(rawEncodedBuffer);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(B64CodeBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
