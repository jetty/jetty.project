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

package org.eclipse.jetty.io.jmh;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
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

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class ByteBufferBenchmark
{
    public long test(ByteBuffer buffer)
    {
        buffer.clear();
        while (buffer.hasRemaining())
        {
            int size = ThreadLocalRandom.current().nextInt(1024);
            byte[] bytes = new byte[size];
            ThreadLocalRandom.current().nextBytes(bytes);
            buffer.put(bytes, 0, Math.min(bytes.length, buffer.remaining()));
        }

        buffer.flip();

        long sum = 0;
        while (buffer.hasRemaining())
        {
            sum += buffer.get();
        }

        return sum;
    }

    public long testArray(ByteBuffer buffer)
    {
        buffer.clear();
        byte[] array = buffer.array();
        int offset = buffer.arrayOffset();
        int end = offset + buffer.remaining();
        while (offset < end)
        {
            int size = ThreadLocalRandom.current().nextInt(1024);
            byte[] bytes = new byte[size];
            ThreadLocalRandom.current().nextBytes(bytes);
            System.arraycopy(bytes, 0, array, offset, Math.min(bytes.length, end - offset));
            offset += bytes.length;
        }
        buffer.position(buffer.limit());
        buffer.flip();

        long sum = 0;
        array = buffer.array();
        offset = buffer.arrayOffset();
        end = offset + buffer.remaining();

        while (offset < end)
        {
            sum += array[offset++];
        }
        buffer.position(buffer.limit());
        return sum;
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testDirect()
    {
        ByteBuffer buffer = ByteBuffer.allocateDirect(32768);
        long sum = 0;
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        return sum;
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testInDirect()
    {
        ByteBuffer buffer = ByteBuffer.allocate(32768);
        long sum = 0;
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        sum ^= test(buffer);
        return sum;
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testInDirectArray()
    {
        ByteBuffer buffer = ByteBuffer.allocate(32768);
        long sum = 0;
        sum ^= testArray(buffer);
        sum ^= testArray(buffer);
        sum ^= testArray(buffer);
        sum ^= testArray(buffer);
        sum ^= testArray(buffer);
        return sum;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ByteBufferBenchmark.class.getSimpleName())
            .warmupIterations(20)
            .measurementIterations(10)
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(10)
            .build();

        new Runner(opt).run();
    }
}
