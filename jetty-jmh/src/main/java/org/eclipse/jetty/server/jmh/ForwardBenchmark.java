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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.Trie;
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

import static java.lang.invoke.MethodType.methodType;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class ForwardBenchmark
{
    static HttpFields __fields = new HttpFields();

    static
    {
        Arrays.stream(new String[][]
            {
                {"accept", "*/*"},
                {"accept-encoding", "gzip, deflate, br"},
                {"accept-language", "en,en-AU;q=0.9,it;q=0.8"},
                {"content-length", "0"},
                {"content-type", "text/plain;charset=UTF-8"},
                {"cookie", "S=maestro=cH6W-eZ0JweknIgCwBxWD4FxQk647Uru:sso=HEiTd0qT5KdU7X6eL1m8snK1jNHwVJ9d"},
                {"origin", "https://www.google.com"},
                {"referer", "https://www.google.com/"},
                {"user-agent", "Mozilla/5.0"},
                {"x-client-data", "CLK1yQEIkLbJAQiltskBCKmdygEIoZ7KAQioo8oBCL+nygEI4qjKAQ=="},
                {"Forwarded", "for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com"}
            }).map(a -> new HttpField(a[0], a[1])).forEach(__fields::add);
    }

    public ForwardBenchmark()
    {
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testStringCompare()
    {
        String forwardedFor = null;
        String forwardedHost = null;
        String forwardedServer = null;
        String forwarded = null;

        for (HttpField field : __fields)
        {
            String name = field.getName();
            if (HttpHeader.X_FORWARDED_FOR.asString().equalsIgnoreCase(name))
                forwardedFor = field.getValue();
            else if (HttpHeader.X_FORWARDED_HOST.asString().equalsIgnoreCase(name))
                forwardedHost = field.getValue();
            else if (HttpHeader.X_FORWARDED_SERVER.asString().equalsIgnoreCase(name))
                forwardedServer = field.getValue();
            else if (HttpHeader.FORWARDED.asString().equalsIgnoreCase(name))
                forwardedServer = field.getValue();
        }

        if (forwardedFor != null)
            return forwardedFor;
        if (forwardedHost != null)
            return forwardedHost;
        if (forwardedServer != null)
            return forwardedServer;
        return forwarded;
    }

    static class Forwarded
    {
        String host;

        public void getHost(HttpField field)
        {
            if (host == null)
                host = field.getValue();
        }
    }

    static Trie<MethodHandle> __handles = new ArrayTrie<>(1024);

    static
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = methodType(Void.TYPE, HttpField.class);

        try
        {
            __handles.put(HttpHeader.X_FORWARDED_FOR.asString(), lookup.findVirtual(Forwarded.class, "getHost", type));
            __handles.put(HttpHeader.X_FORWARDED_HOST.asString(), lookup.findVirtual(Forwarded.class, "getHost", type));
            __handles.put(HttpHeader.X_FORWARDED_SERVER.asString(), lookup.findVirtual(Forwarded.class, "getHost", type));
            __handles.put(HttpHeader.FORWARDED.asString(), lookup.findVirtual(Forwarded.class, "getHost", type));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testTrieMethodHandle()
    {
        Forwarded forwarded = new Forwarded();

        try
        {
            for (HttpField field : __fields)
            {
                MethodHandle handle = __handles.get(field.getName());
                if (handle != null)
                    handle.invoke(forwarded, field);
            }
        }
        catch (Throwable e)
        {
            e.printStackTrace();
        }

        return forwarded.host;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ForwardBenchmark.class.getSimpleName())
            .warmupIterations(20)
            .measurementIterations(10)
            .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(100)
            .build();

        new Runner(opt).run();
    }
}
