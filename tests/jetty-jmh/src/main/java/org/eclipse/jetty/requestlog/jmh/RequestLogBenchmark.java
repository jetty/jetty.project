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

package org.eclipse.jetty.requestlog.jmh;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.TypeUtil;
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

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodType.methodType;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class RequestLogBenchmark
{

    public static void append(String s, StringBuilder b)
    {
        b.append(s);
    }

    public static void logURI(StringBuilder b, String request)
    {
        b.append(request);
    }

    public static void logLength(StringBuilder b, String request)
    {
        b.append(request.length());
    }

    public static void logAddr(StringBuilder b, String request)
    {
        try
        {
            TypeUtil.toHex(request.hashCode(), b);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private ThreadLocal<StringBuilder> buffers = ThreadLocal.withInitial(() -> new StringBuilder(256));
    MethodHandle logHandle;
    Object[] iteratedLog;

    public RequestLogBenchmark()
    {
        try
        {
            MethodType logType = methodType(Void.TYPE, StringBuilder.class, String.class);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle append = lookup.findStatic(RequestLogBenchmark.class, "append", methodType(Void.TYPE, String.class, StringBuilder.class));
            MethodHandle logURI = lookup.findStatic(RequestLogBenchmark.class, "logURI", logType);
            MethodHandle logAddr = lookup.findStatic(RequestLogBenchmark.class, "logAddr", logType);
            MethodHandle logLength = lookup.findStatic(RequestLogBenchmark.class, "logLength", logType);

            // setup iteration
            iteratedLog = new Object[]
            {
                logURI,
                " - ",
                logAddr,
                " ",
                logLength,
                "\n"
            };

            // setup methodHandle
            logHandle = dropArguments(append.bindTo("\n"), 1, String.class);
            logHandle = foldArguments(logHandle, logLength);
            logHandle = foldArguments(logHandle, dropArguments(append.bindTo(" "), 1, String.class));
            logHandle = foldArguments(logHandle, logAddr);
            logHandle = foldArguments(logHandle, dropArguments(append.bindTo(" - "), 1, String.class));
            logHandle = foldArguments(logHandle, logURI);
        }
        catch (Throwable th)
        {
            throw new RuntimeException(th);
        }
    }

    public String logFixed(String request)
    {
        StringBuilder b = buffers.get();
        logURI(b, request);
        append(" - ", b);
        logAddr(b, request);
        append(" ", b);
        logLength(b, request);
        append("\n", b);
        String l = b.toString();
        b.setLength(0);
        return l;
    }

    public String logIterate(String request)
    {
        try
        {

            StringBuilder b = buffers.get();
            for (Object o : iteratedLog)
            {
                if (o instanceof String)
                    append((String)o, b);
                else if (o instanceof MethodHandle)
                    ((MethodHandle)o).invoke(b, request);
            }
            String l = b.toString();
            b.setLength(0);
            return l;
        }
        catch (Throwable th)
        {
            throw new RuntimeException(th);
        }
    }

    public String logMethodHandle(String request)
    {
        try
        {
            StringBuilder b = buffers.get();
            logHandle.invoke(b, request);
            String l = b.toString();
            b.setLength(0);
            return l;
        }
        catch (Throwable th)
        {
            throw new RuntimeException(th);
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testFixed()
    {
        return logFixed(Long.toString(ThreadLocalRandom.current().nextLong()));
    }

    ;

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testIterate()
    {
        return logIterate(Long.toString(ThreadLocalRandom.current().nextLong()));
    }

    ;

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testHandle()
    {
        return logMethodHandle(Long.toString(ThreadLocalRandom.current().nextLong()));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(RequestLogBenchmark.class.getSimpleName())
            .warmupIterations(20)
            .measurementIterations(10)
            .addProfiler(GCProfiler.class)
            .forks(1)
            .threads(100)
            .build();

        new Runner(opt).run();
    }
}
