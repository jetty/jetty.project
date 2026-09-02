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

package org.eclipse.jetty.util.jmh;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.StringUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * <p>Benchmarks of {@link StringUtil#asciiToLowerCase(String)} and
 * {@link StringUtil#asciiToUpperCase(String)}, which HTTP header name and
 * value handling calls constantly. The common case is a string that is
 * already in the target case and needs no conversion at all, so both a
 * short and a long such string are covered, alongside strings that do need
 * converting, with the character that forces the conversion placed either
 * near the start or near the end.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class StringUtilBenchmark
{
    private static final String SHORT_LOWER = "id";
    private static final String TYPICAL_LOWER = "content-type";
    private static final String TYPICAL_MIXED = "Content-Type";
    private static final String LONG_LOWER = "x-request-id-correlation-trace-span-baggage-context";
    private static final String LONG_LATE_MIXED = "x-request-id-correlation-trace-span-baggage-contexT";

    private static final String SHORT_UPPER = "ID";
    private static final String TYPICAL_UPPER = "CONTENT-TYPE";
    private static final String LONG_UPPER = "X-REQUEST-ID-CORRELATION-TRACE-SPAN-BAGGAGE-CONTEXT";
    private static final String LONG_LATE_MIXED_UPPER = "X-REQUEST-ID-CORRELATION-TRACE-SPAN-BAGGAGE-CONTEXt";

    @Benchmark
    public String asciiToLowerCaseShortNoChange()
    {
        return StringUtil.asciiToLowerCase(SHORT_LOWER);
    }

    @Benchmark
    public String asciiToLowerCaseTypicalNoChange()
    {
        return StringUtil.asciiToLowerCase(TYPICAL_LOWER);
    }

    @Benchmark
    public String asciiToLowerCaseTypicalMixed()
    {
        return StringUtil.asciiToLowerCase(TYPICAL_MIXED);
    }

    @Benchmark
    public String asciiToLowerCaseLongNoChange()
    {
        return StringUtil.asciiToLowerCase(LONG_LOWER);
    }

    @Benchmark
    public String asciiToLowerCaseLongLateConversion()
    {
        return StringUtil.asciiToLowerCase(LONG_LATE_MIXED);
    }

    @Benchmark
    public String asciiToUpperCaseShortNoChange()
    {
        return StringUtil.asciiToUpperCase(SHORT_UPPER);
    }

    @Benchmark
    public String asciiToUpperCaseTypicalNoChange()
    {
        return StringUtil.asciiToUpperCase(TYPICAL_UPPER);
    }

    @Benchmark
    public String asciiToUpperCaseTypicalMixed()
    {
        return StringUtil.asciiToUpperCase(TYPICAL_MIXED);
    }

    @Benchmark
    public String asciiToUpperCaseLongNoChange()
    {
        return StringUtil.asciiToUpperCase(LONG_UPPER);
    }

    @Benchmark
    public String asciiToUpperCaseLongLateConversion()
    {
        return StringUtil.asciiToUpperCase(LONG_LATE_MIXED_UPPER);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options options = new OptionsBuilder()
            .include(StringUtilBenchmark.class.getSimpleName())
            .forks(1)
            .build();
        new Runner(options).run();
    }
}
