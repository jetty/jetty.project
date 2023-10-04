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

import org.eclipse.jetty.websocket.core.util.BindingMethodHolder2;
import org.eclipse.jetty.websocket.core.util.LambdaMetafactoryMethodHolder;
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

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class MethodHolderBenchmark
{
    private MethodHandle methodHandle;
    private MethodHolder bindingMethodHolder;
    private MethodHolder nonBindingMethodHolder;
    private BindingMethodHolder2 methodHolderWithOptimisation;
    private LambdaMetafactoryMethodHolder lambdaMetafactoryMethodHolder;

    public static void main(String[] args) throws Throwable
    {
        MethodType methodType = MethodType.methodType(void.class, Blackhole.class, String.class, String.class);
        MethodHandle methodHandle = MethodHandles.lookup()
            .findVirtual(MethodHolderBenchmark.class, "method87964376", methodType);
        if (methodHandle == null)
            throw new IllegalStateException();

        LambdaMetafactoryMethodHolder lambdaMetafactoryMethodHolder = new LambdaMetafactoryMethodHolder(methodHandle, MethodHandles.lookup());
        lambdaMetafactoryMethodHolder = lambdaMetafactoryMethodHolder.bindTo(null);
        lambdaMetafactoryMethodHolder = lambdaMetafactoryMethodHolder.bindTo(null);
        System.err.println(lambdaMetafactoryMethodHolder);
    }

    @Setup(Level.Trial)
    public void setupTrial(Blackhole blackhole) throws Throwable
    {
        MethodType methodType = MethodType.methodType(void.class, Blackhole.class, String.class, String.class);
        methodHandle = MethodHandles.lookup()
            .findVirtual(MethodHolderBenchmark.class, "method87964376", methodType);
        if (methodHandle == null)
            throw new IllegalStateException();

        bindingMethodHolder = MethodHolder.from(methodHandle, true);
        bindingMethodHolder.bindTo(this);
        bindingMethodHolder.bindTo(Objects.requireNonNull(blackhole));

        nonBindingMethodHolder = MethodHolder.from(methodHandle, false);
        nonBindingMethodHolder.bindTo(this);
        nonBindingMethodHolder.bindTo(Objects.requireNonNull(blackhole));

        methodHolderWithOptimisation = new BindingMethodHolder2(methodHandle);
        methodHolderWithOptimisation = methodHolderWithOptimisation.bindTo(this);
        methodHolderWithOptimisation = methodHolderWithOptimisation.bindTo(Objects.requireNonNull(blackhole));

        optimisedMethodHandle = methodHolderWithOptimisation.getMethodHandler();

        lambdaMetafactoryMethodHolder = new LambdaMetafactoryMethodHolder(methodHandle, MethodHandles.lookup());
        lambdaMetafactoryMethodHolder = lambdaMetafactoryMethodHolder.bindTo(this);
        lambdaMetafactoryMethodHolder = lambdaMetafactoryMethodHolder.bindTo(Objects.requireNonNull(blackhole));


        methodHandle = methodHandle.bindTo(this);
        methodHandle = methodHandle.bindTo(Objects.requireNonNull(blackhole));
    }

    private MethodHandle optimisedMethodHandle;

    public void method87964376(Blackhole blackhole, String a, String b)
    {
        blackhole.consume(a);
        blackhole.consume(b);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void methodHandleTest() throws Throwable
    {
        methodHandle.invoke("test", "12");
    }

//    @Benchmark
//    @BenchmarkMode({Mode.Throughput})
//    public void methodHandleWithArgumentsTest() throws Throwable
//    {
//        methodHandle.invokeWithArguments("test", "12");
//    }
//
//    @Benchmark
//    @BenchmarkMode({Mode.Throughput})
//    public void methodHandleWithArgumentsArrayTest() throws Throwable
//    {
//        methodHandle.invokeWithArguments(new Object[]{"test", "12"});
//    }

//    @Benchmark
//    @BenchmarkMode({Mode.Throughput})
//    public void bindingMethodHolderTest() throws Throwable
//    {
//        bindingMethodHolder.invoke("test", "12");
//    }

//    @Benchmark
//    @BenchmarkMode({Mode.Throughput})
//    public void nonBindingMethodHolderTest() throws Throwable
//    {
//        nonBindingMethodHolder.invoke("test", "12");
//    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void methodHolderWithOptimisationTest() throws Throwable
    {
        methodHolderWithOptimisation.invoke("test", "12");
//        optimisedMethodHandle.invoke("test", "12");
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void lambdaMetafactoryTest() throws Throwable
    {
        lambdaMetafactoryMethodHolder.invoke("test", "12");
    }

//    public static void main(String[] args) throws RunnerException
//    {
//        Options opt = new OptionsBuilder()
//            .include(MethodHolderBenchmark.class.getSimpleName())
//            .warmupIterations(5)
//            .measurementIterations(10)
//            .addProfiler(LinuxPerfAsmProfiler.class)
////            .addProfiler(GCProfiler.class)
//            .forks(1)
//            .threads(1)
//            .build();
//
//        new Runner(opt).run();
//    }
}


