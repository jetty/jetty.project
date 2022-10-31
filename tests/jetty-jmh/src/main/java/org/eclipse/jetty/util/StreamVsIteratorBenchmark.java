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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 3)
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class StreamVsIteratorBenchmark
{
    @Param({"0", "1", "10", "100"})
    int size;

    Supplier<Iterator<Object>> _iteratorSupplier;
    Supplier<Stream<Object>> _streamSupplier;

    @Setup(Level.Trial)
    public void setupTrial()
    {
        switch (size)
        {
            case 0 ->
            {
                _iteratorSupplier = Collections.emptyList()::iterator;
                _streamSupplier = Stream::empty;
            }
            case 1 ->
            {
                Object item = System.nanoTime();
                _iteratorSupplier = List.of(item)::iterator;
                _streamSupplier = () -> Stream.of(item);
            }
            default ->
            {
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < size; i++)
                    list.add(System.nanoTime());
                _iteratorSupplier = list::iterator;
                _streamSupplier = list::stream;
            }
        }
    }

    @Benchmark
    public long testIterator()
    {
        long result = 0;
        for (Iterator<Object> i = _iteratorSupplier.get(); i.hasNext();)
        {
            Object o = i.next();
            long h = o.hashCode();
            if (h % 2 == 0)
                result ^= h;
        }
        return result;
    }

    @Benchmark
    public long testStream()
    {
        return _streamSupplier.get().mapToLong(Objects::hashCode).filter(h -> h % 2 != 0).sum();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(StreamVsIteratorBenchmark.class.getSimpleName())
//            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
