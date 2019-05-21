//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.TreeTrie;
import org.eclipse.jetty.util.Trie;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(1)
@Warmup(iterations = 6, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class ListVsTrieBenchmark
{
    @Param({ "12" })   // Chrome has 12 for HTTP/1.1 and 16 for HTTP/2 (including meta headers)
    public static int size;

    @Param({ "11" })  // average length of known headers in HttpHeader
    public static int length;

    @Param({"1", "10", "20" })
    public static int lookups;

    @Param({"hits", "nears", "misses" })
    public static String mostly;

    static final String base = "This-is-the-base-of-All-key-names-and-is-long".substring(0,length);
    static final String near = base + "-X";
    static final String miss = "X-" + base;
    static final List<String> trials = new ArrayList<>();

    @Setup(Level.Trial)
    public void setup()
    {
        int hits = 1;
        int nearMisses = 1;
        int misses = 1;
        switch(mostly)
        {
            case "hits" : hits = lookups; break;
            case "nears" : nearMisses = lookups; break;
            case "misses" : misses = lookups; break;
            default : throw new IllegalStateException();
        }

        int hit = size / 2;
        for (int h = hits; h-->0;)
            trials.add(base + "-" + ((hit++) % size));

        for (int n = nearMisses; n-->0; )
            trials.add(near);

        for (int m = misses; m-->0; )
            trials.add(miss);

        Collections.shuffle(trials);
    }


    static class Pair
    {
        final String key;
        final long value;

        public Pair(String key, long value)
        {
            this.key = key;
            this.value = value;
        }
    }

    interface Fill
    {
        void put(Pair p);
    }

    interface Lookup
    {
        Pair get(String key);
    }

    private void fill(Fill fill)
    {
        for (int i=0; i<size; i++)
        {
            String key = base + "-" + i;
            Pair pair = new Pair(key, key.hashCode());
            fill.put(pair);
        }
    }

    private long test(Lookup lookup)
    {
        long result = 0;
        for (String t : trials)
        {
            Pair p = lookup.get(t);
            if (p!=null)
                result ^= p.value;
        }

        return result;
    }

    private long listLookup(List<Pair> list)
    {
        return test(k->
        {
            for (int i = 0; i<list.size(); i++ )
            {
                Pair p = list.get(i);
                if (p.key.equalsIgnoreCase(k))
                    return p;
            }
            return null;
        });
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testList() throws Exception
    {
        List<Pair> list = new ArrayList<>(size);
        fill(list::add);
        return listLookup(list);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testTreeTrie() throws Exception
    {
        Trie<Pair> trie = new TreeTrie<>();
        fill(p->trie.put(p.key,p));
        return test(trie::get);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testArrayTrie() throws Exception
    {
        Trie<Pair> trie = new ArrayTrie<>(length*size);
        fill(p->trie.put(p.key,p));
        return test(trie::get);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testMap() throws Exception
    {
        Map<String,Pair> map = new HashMap<>(size);
        fill(p->map.put(p.key.toLowerCase(),p));
        return test(k->map.get(k.toLowerCase()));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ListVsTrieBenchmark.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}


