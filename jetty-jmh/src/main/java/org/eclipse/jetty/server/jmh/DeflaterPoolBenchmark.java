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

package org.eclipse.jetty.server.jmh;

import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import org.eclipse.jetty.util.compression.DeflaterPool;
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
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class DeflaterPoolBenchmark
{
    public static final String COMPRESSION_STRING = "hello world";
    DeflaterPool _pool;

    @Param({"NO_POOL", "DEFLATER_POOL_10", "DEFLATER_POOL_20", "DEFLATER_POOL_50"})
    public static String poolType;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception
    {
        int capacity;

        switch (poolType)
        {
            case "NO_POOL":
                capacity = 0;
                break;

            case "DEFLATER_POOL_10":
                capacity = 10;
                break;

            case "DEFLATER_POOL_20":
                capacity = 20;
                break;

            case "DEFLATER_POOL_50":
                capacity = 50;
                break;

            default:
                throw new IllegalStateException("Unknown poolType Parameter");
        }

        _pool = new DeflaterPool(capacity, Deflater.DEFAULT_COMPRESSION, true);
    }

    @TearDown(Level.Trial)
    public static void stopTrial() throws Exception
    {
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @SuppressWarnings("deprecation")
    public long testPool() throws Exception
    {
        Deflater deflater = _pool.acquire();
        deflater.setInput(COMPRESSION_STRING.getBytes());
        deflater.finish();

        byte[] output = new byte[COMPRESSION_STRING.length() + 1];
        int compressedDataLength = deflater.deflate(output);
        _pool.release(deflater);

        return compressedDataLength;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(DeflaterPoolBenchmark.class.getSimpleName())
            .warmupIterations(20)
            .measurementIterations(10)
            .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(100)
            .build();

        new Runner(opt).run();
    }
}


