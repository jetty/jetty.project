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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private static final String[] HEADER_NAMES = {
        "Content-Type", "Content-Length", "User-Agent", "Accept", "Authorization"
    };

    @Benchmark
    @OperationsPerInvocation(5)
    public long testHashMapBuildAndLookup()
    {
        // Build the HashMap
        Map<String, String> hashMap = new HashMap<>();
        hashMap.put("Content-Type", "application/json");
        hashMap.put("Content-Length", "123");
        hashMap.put("User-Agent", "JMH Benchmark");
        hashMap.put("Accept", "application/json");
        hashMap.put("Authorization", "Bearer token");

        // Perform lookups
        long result = 0;
        for (String header : HEADER_NAMES)
        {
            result ^= hashMap.get(header).hashCode();
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(5)
    public long testEnumMapBuildAndLookup()
    {
        // Build the EnumMap
        Map<HttpHeader, String> enumMap = new EnumMap<>(HttpHeader.class);
        enumMap.put(HttpHeader.CONTENT_TYPE, "application/json");
        enumMap.put(HttpHeader.CONTENT_LENGTH, "123");
        enumMap.put(HttpHeader.USER_AGENT, "JMH Benchmark");
        enumMap.put(HttpHeader.ACCEPT, "application/json");
        enumMap.put(HttpHeader.AUTHORIZATION, "Bearer token");

        // Perform lookups
        long result = 0;
        for (HttpHeader header : HEADERS)
        {
            result ^= enumMap.get(header).hashCode();
        }
        return result;
    }

    public enum HttpHeader
    {
        CONTENT_TYPE,
        CONTENT_LENGTH,
        USER_AGENT,
        ACCEPT,
        AUTHORIZATION
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
