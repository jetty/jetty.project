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

package org.eclipse.jetty.http.jmh;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.BufferUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class HttpMethodBenchmark
{
    private static final ByteBuffer GET = BufferUtil.toBuffer("GET / HTTP/1.1\r\n\r\n");
    private static final ByteBuffer POST = BufferUtil.toBuffer("POST / HTTP/1.1\r\n\r\n");
    private static final ByteBuffer MOVE = BufferUtil.toBuffer("MOVE / HTTP/1.1\r\n\r\n");
    private static final Map<String, HttpMethod> MAP = new HashMap<>();

    static
    {
        for (HttpMethod m : HttpMethod.values())
            MAP.put(m.asString(), m);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public HttpMethod testTrieGetBest() throws Exception
    {
        return HttpMethod.LOOK_AHEAD.getBest(GET, 0, GET.remaining());
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public HttpMethod testIntSwitch() throws Exception
    {
        switch (GET.getInt(0))
        {
            case HttpMethod.ACL_AS_INT:
                return HttpMethod.ACL;
            case HttpMethod.GET_AS_INT:
                return HttpMethod.GET;
            case HttpMethod.PRI_AS_INT:
                return HttpMethod.PRI;
            case HttpMethod.PUT_AS_INT:
                return HttpMethod.PUT;
            default:
                return null;
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public HttpMethod testMapGet() throws Exception
    {
        for (int i = 0; i < GET.remaining(); i++)
        {
            if (GET.get(i) == (byte)' ')
                return MAP.get(BufferUtil.toString(GET, 0, i, StandardCharsets.US_ASCII));
        }
        return null;
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public HttpMethod testHttpMethodPost() throws Exception
    {
        return HttpMethod.lookAheadGet(POST);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public HttpMethod testHttpMethodMove() throws Exception
    {
        return HttpMethod.lookAheadGet(MOVE);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(HttpMethodBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .measurementIterations(10)
            .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
    }
}


