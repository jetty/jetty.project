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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpURI;
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
 * <p>Benchmarks of URI parsing, which every request pays for, over HTTP/1
 * where the request line carries the URI and over HTTP/2 where the :path
 * pseudo header does.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class HttpURIBenchmark
{
    private static final String SHORT_PATH = "/index.html";
    private static final String TYPICAL_PATH_QUERY = "/some/resource/path?name=value&other=thing";
    private static final String LONG_PATH = "/api/v2/organisations/12345/repositories/jetty/issues/6789/comments/recent";
    private static final String ENCODED_PATH = "/some/resource/f%6fo%2fbar%20bob/path";
    private static final String PARAM_PATH = "/some/resource;jsessionid=8f14e45fceea167a5a36dedd4bea2543/path";
    private static final String ABSOLUTE = "https://example.org/some/resource/path?name=value&other=thing";

    @Benchmark
    public String parseShortPath()
    {
        return HttpURI.build().pathQuery(SHORT_PATH).asImmutable().getPath();
    }

    @Benchmark
    public String parseTypicalPathQuery()
    {
        return HttpURI.build().pathQuery(TYPICAL_PATH_QUERY).asImmutable().getPath();
    }

    @Benchmark
    public String parseLongPath()
    {
        return HttpURI.build().pathQuery(LONG_PATH).asImmutable().getPath();
    }

    @Benchmark
    public String parseEncodedPath()
    {
        return HttpURI.build().pathQuery(ENCODED_PATH).asImmutable().getPath();
    }

    @Benchmark
    public String parseParamPath()
    {
        return HttpURI.build().pathQuery(PARAM_PATH).asImmutable().getPath();
    }

    @Benchmark
    public String parseAbsoluteUri()
    {
        return HttpURI.from(ABSOLUTE).getPath();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options options = new OptionsBuilder()
            .include(HttpURIBenchmark.class.getSimpleName())
            .forks(1)
            .build();
        new Runner(options).run();
    }
}
