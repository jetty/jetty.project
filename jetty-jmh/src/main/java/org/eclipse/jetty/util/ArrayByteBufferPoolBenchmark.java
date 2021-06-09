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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class ArrayByteBufferPoolBenchmark
{
    private ByteBufferPool pool;

    @Setup
    public void setUp() throws Exception
    {
        pool = new ArrayByteBufferPool();
    }

    @TearDown
    public void tearDown()
    {
        pool = null;
    }

    @Benchmark
    public void testAcquireRelease()
    {
        ByteBuffer buffer = pool.acquire(2048, true);
        pool.release(buffer);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ArrayByteBufferPoolBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .measurementIterations(3)
            .forks(1)
            .threads(8)
            // .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
