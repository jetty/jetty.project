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

package org.eclipse.jetty.util;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 9, time = 800, timeUnit = TimeUnit.MILLISECONDS)
public class StringIsEmptyBenchmark
{

    private static final String SHORT = "beer.com/foo";

    private static final String MEDIUM = "beer.com/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde";

    private static final String LONG = "beer.com/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde";

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortIsEmpty()
    {
        StringUtil.isEmpty(SHORT);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void mediumIsEmpty()
    {
        StringUtil.isEmpty(MEDIUM);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longIsEmpty()
    {
        StringUtil.isEmpty(LONG);
    }
}
