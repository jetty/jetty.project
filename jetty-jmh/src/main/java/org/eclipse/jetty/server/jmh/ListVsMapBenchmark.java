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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

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
public class ListVsMapBenchmark
{
    @Param({ "12" })   // Chrome has 12 for HTTP/1.1 and 16 for HTTP/2 (including meta headers)
    public static int size;

    @Param({ "11" })  // average length of known headers in HttpHeader
    public static int length;

    @Param({"1", "10", "20" })
    public static int lookups;

    @Param({"hits", "misses", "iterate" })
    public static String mostly;

    static final String base = "This-is-the-base-of-All-key-names-and-is-long".substring(0,length);
    static final String miss = "X-" + base;
    static final List<String> trials = new ArrayList<>();

    @Setup(Level.Trial)
    public void setup()
    {
        int hits = 1;
        int misses = 1;
        switch(mostly)
        {
            case "hits" : hits = lookups; break;
            case "misses" : misses = lookups; break;
            case "iterate" : hits = lookups/2; misses=lookups-hits; break;
            default : throw new IllegalStateException();
        }

        int hit = size / 2;
        for (int h = hits; h-->0;)
            trials.add(base + "-" + ((hit++) % size));

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
        Iterator<Pair> iterate();
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
        if ("iterate".equals(mostly))
        {
            Iterator<String> t = trials.iterator();
            while(t.hasNext())
            {
                String one = t.hasNext() ? t.next() : null;
                String two = t.hasNext() ? t.next() : null;
                String three = t.hasNext() ? t.next() : null;
                String four = t.hasNext() ? t.next() : null;

                Iterator<Pair> i = lookup.iterate();
                while (i.hasNext())
                {
                    Pair p = i.next();
                    String k = p.key;
                    if (one != null && one.equals(k))
                        result ^= p.value;
                    else if (two != null && one.equals(k))
                        result ^= p.value;
                    else if (three != null && one.equals(k))
                        result ^= p.value;
                    else if (four != null && one.equals(k))
                        result ^= p.value;
                }
            }
        }
        else
        {
            for (String t : trials)
            {
                Pair p = lookup.get(t);
                if (p != null)
                    result ^= p.value;
            }
        }

        return result;
    }

    private long listLookup(List<Pair> list)
    {
        return test(new Lookup() {
            @Override
            public Pair get(String k)
            {
                for (int i = 0; i<list.size(); i++ )
                {
                    Pair p = list.get(i);
                    if (p.key.equalsIgnoreCase(k))
                        return p;
                }
                return null;
            }

            @Override
            public Iterator<Pair> iterate()
            {
                return list.iterator();
            }
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
    public long testSortedMap() throws Exception
    {
        Map<String,List<Pair>> map = new LinkedHashMap<>(size);
        fill(p->
        {
            List<Pair> list = new ArrayList<>();
            list.add(p);
            map.put(p.key.toLowerCase(),list);
        });
        return test(new Lookup()
        {
            @Override
            public Pair get(String k)
            {
                List<Pair> list = map.get(k.toLowerCase());
                if (list == null || list.isEmpty())
                    return null;
                return list.get(0);
            }

            @Override
            public Iterator<Pair> iterate()
            {
                Iterator<List<Pair>> iter = map.values().iterator();

                return new Iterator<Pair>() {
                    Iterator<Pair> current;
                    @Override
                    public boolean hasNext()
                    {
                        if (( current==null || !current.hasNext() ) && iter.hasNext())
                            current=iter.next().iterator();
                        return current!=null && current.hasNext();
                    }

                    @Override
                    public Pair next()
                    {
                        if (hasNext())
                            return current.next();
                        throw new NoSuchElementException();
                    }
                };
            }
        });
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ListVsMapBenchmark.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}


