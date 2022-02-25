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

package org.eclipse.jetty.util;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 3)
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class StringReplaceBenchmark
{
    @Param({"3", "100", "1000"})
    int size;

    @Param({"0", "1", "3", "50"})
    int matches;

    String input;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception
    {
        String pattern = "abc";
        StringBuilder str = new StringBuilder();
        while (str.length() < size)
        {
            str.append(pattern);
        }

        if (matches > 0)
        {
            int partSize = (int)((double)str.length() / (double)matches);
            for (int i = 0; i < matches; i++)
            {
                str.insert((i * partSize), "'");
            }
        }
        input = str.toString();
    }

    @Benchmark
    public void testJavaStringReplaceGrowth(Blackhole blackhole)
    {
        blackhole.consume(input.replace("'", "FOOBAR"));
    }

    @Benchmark
    public void testJavaStringReplaceSame(Blackhole blackhole)
    {
        blackhole.consume(input.replace("'", "X"));
    }

    @Benchmark
    public void testJavaStringReplaceReduce(Blackhole blackhole)
    {
        blackhole.consume(input.replace("'", ""));
    }

    @Benchmark
    public void testJettyStringUtilReplaceGrowth(Blackhole blackhole)
    {
        blackhole.consume(StringUtil.replace(input, "'", "FOOBAR"));
    }

    @Benchmark
    public void testJettyStringUtilReplaceSame(Blackhole blackhole)
    {
        blackhole.consume(StringUtil.replace(input, "'", "X"));
    }

    @Benchmark
    public void testJettyStringUtilReplaceReduce(Blackhole blackhole)
    {
        blackhole.consume(StringUtil.replace(input, "'", ""));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(StringReplaceBenchmark.class.getSimpleName())
//            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
