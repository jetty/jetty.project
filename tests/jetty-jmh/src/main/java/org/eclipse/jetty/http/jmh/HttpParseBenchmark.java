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

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.BufferUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
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
@Warmup(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class HttpParseBenchmark
{
    private static final long GET_SLASH_HT_AS_LONG = stringAsLong("GET / HT");
    private static final long TP_SLASH_1_0_CRLF = stringAsLong("TP/1.0\r\n");
    private static final long TP_SLASH_1_1_CRLF = stringAsLong("TP/1.1\r\n");

    private static long stringAsLong(String s)
    {
        if (s == null || s.length() != 8)
            throw new IllegalArgumentException();
        long l = 0;
        for (char c : s.toCharArray())
        {
            l = l << 8 | ((long)c & 0xFFL);
        }
        return l;
    }

    private static final ByteBuffer GET = BufferUtil.toBuffer("GET / HTTP/1.1\r\n\r\n");
    private static final ByteBuffer POST = BufferUtil.toBuffer("POST / HTTP/1.1\r\n\r\n");

    record RequestLine(String method, String uri, HttpVersion version)
    {
        @Override
        public String toString()
        {
            return "%s %s %s".formatted(method, uri, version);
        }
    }

    @Param({"100", "10", "1", "0"})
    int hits;

    public static RequestLine parse(ByteBuffer buffer)
    {
        HttpMethod method = HttpMethod.lookAheadGet(buffer);
        if (method == null)
            return null;
        buffer.position(buffer.position() + method.asString().length() + 1);

        StringBuilder uri = new StringBuilder();
        while (buffer.hasRemaining())
        {
            byte b = buffer.get();
            if (b == ' ')
                break;
            uri.append((char)b);
        }

        HttpVersion httpVersion = HttpVersion.CACHE.getBest(buffer);
        buffer.position(buffer.position() + httpVersion.asString().length());
        if (buffer.get() != '\r')
            return null;
        if (buffer.get() != '\n')
            return null;

        return new RequestLine(method.asString(), uri.toString(), httpVersion);
    }

    public static RequestLine lookAhead(ByteBuffer buffer)
    {
        if (buffer.getLong(0) != GET_SLASH_HT_AS_LONG)
            return parse(buffer);
        long v = buffer.getLong(8);
        if (v == TP_SLASH_1_1_CRLF)
        {
            buffer.position(buffer.position() + 16);
            return new RequestLine(HttpMethod.GET.asString(), "/", HttpVersion.HTTP_1_1);
        }
        if (v == TP_SLASH_1_0_CRLF)
        {
            buffer.position(buffer.position() + 16);
            return new RequestLine(HttpMethod.GET.asString(), "/", HttpVersion.HTTP_1_0);
        }
        return parse(buffer);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public RequestLine testParse()
    {
        ByteBuffer request = (ThreadLocalRandom.current().nextInt(100) < hits) ? GET : POST;
        return parse(request.slice());
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public RequestLine testLookAhead()
    {
        ByteBuffer request = (ThreadLocalRandom.current().nextInt(100) < hits) ? GET : POST;
        return lookAhead(request.slice());
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(HttpParseBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .measurementIterations(10)
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
    }
}


