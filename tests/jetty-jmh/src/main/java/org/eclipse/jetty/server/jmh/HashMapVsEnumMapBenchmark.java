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

package org.eclipse.jetty.server.jmh;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@Fork(1)
@Warmup(iterations = 6, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class HashMapVsEnumMapBenchmark
{
    private static final HttpHeader[] HEADERS = HttpHeader.values();
    private static final HttpHeader[] HEADER_NAMES =
    {
        // These will be hits
        HttpHeader.HOST,
        HttpHeader.CONTENT_TYPE,
        HttpHeader.CONTENT_LENGTH,
        HttpHeader.ACCEPT,

        // These will be misses
        HttpHeader.TRANSFER_ENCODING,
        HttpHeader.AUTHORIZATION
    };

    private List<HttpField> newHeaders()
    {
        List<HttpField> list = new ArrayList<>();
        list.add(new HttpField(HttpHeader.HOST, "Localhost"));
        list.add(new HttpField(HttpHeader.CONTENT_TYPE, "application/json"));
        list.add(new HttpField(HttpHeader.CONTENT_LENGTH, "123"));
        list.add(new HttpField(HttpHeader.USER_AGENT, "JMH Benchmark"));
        list.add(new HttpField(HttpHeader.ACCEPT, "application/json"));
        return list;
    }

    @Benchmark
    @OperationsPerInvocation(5)
    public long testListLookup()
    {
        // Build the HashMap
        List<HttpField> list = newHeaders();

        // Perform lookups
        long result = 0;
        for (HttpHeader header : HEADER_NAMES)
        {
            for (HttpField field : list)
            {
                if (field.getHeader() == header)
                {
                    result ^= field.getValue().hashCode();
                    break;
                }
            }
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(5)
    public long testHashMapBuildAndLookup()
    {
        // Build the HashMap
        List<HttpField> list = newHeaders();
        Map<String, HttpField> hashMap = new HashMap<>();
        for (HttpField field : list)
        {
            hashMap.put(field.getName(), field);
        }

        // Perform lookups
        long result = 0;
        for (HttpHeader header : HEADER_NAMES)
        {
            HttpField field = hashMap.get(header.asString());
            if (field != null)
                result ^= field.getValue().hashCode();
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(5)
    public long testEnumMapBuildAndLookup()
    {
        // Build the EnumMap
        Map<HttpHeader, HttpField> enumMap = new EnumMap<>(HttpHeader.class);

        List<HttpField> list = newHeaders();
        for (HttpField field : list)
        {
            enumMap.put(field.getHeader(), field);
        }

        // Perform lookups
        long result = 0;
        for (HttpHeader header : HEADERS)
        {
            HttpField field = enumMap.get(header);
            if (field != null)
                result ^= field.getValue().hashCode();
        }
        return result;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(HashMapVsEnumMapBenchmark.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
