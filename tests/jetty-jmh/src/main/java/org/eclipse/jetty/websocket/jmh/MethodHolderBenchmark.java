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

package org.eclipse.jetty.websocket.jmh;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class MethodHolderBenchmark
{
    private MethodHandle methodHandle;
    private MethodHolder methodHolderNonBinding;
    private MethodHolder methodHolderBinding;

    @Setup(Level.Trial)
    public void setupTrial(Blackhole blackhole) throws Throwable
    {
        MethodType methodType = MethodType.methodType(void.class, Blackhole.class, String.class, String.class);
        methodHandle = MethodHandles.lookup()
            .findVirtual(MethodHolderBenchmark.class, "consume", methodType);
        if (methodHandle == null)
            throw new IllegalStateException();

        methodHolderBinding = MethodHolder.from(methodHandle, true);
        methodHolderBinding.bindTo(this);
        methodHolderBinding.bindTo(Objects.requireNonNull(blackhole));

        methodHolderNonBinding = MethodHolder.from(methodHandle, false);
        methodHolderNonBinding.bindTo(this);
        methodHolderNonBinding.bindTo(Objects.requireNonNull(blackhole));

        methodHandle = methodHandle.bindTo(this);
        methodHandle = methodHandle.bindTo(Objects.requireNonNull(blackhole));
    }

    public void consume(Blackhole blackhole, String a, String b)
    {
        blackhole.consume(a);
        blackhole.consume(b);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void methodHandle() throws Throwable
    {
        methodHandle.invoke("test", "12");
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void methodHolderNonBinding() throws Throwable
    {
        methodHolderNonBinding.invoke("test", "12");
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void methodHolderBinding() throws Throwable
    {
        methodHolderBinding.invoke("test", "12");
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(MethodHolderBenchmark.class.getSimpleName())
            .warmupIterations(1)
            .measurementIterations(5)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
    }
}


