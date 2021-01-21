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

package org.eclipse.jetty.server.jmh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
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
    @Param({"12"})   // Chrome has 12 for HTTP/1.1 and 16 for HTTP/2 (including meta headers)
    public static int size;

    @Param({"11"})  // average length of known headers in HttpHeader
    public static int length;

    @Param({"1", "10", "20", "30"})
    public static int lookups;

    @Param({"hits", "misses", "iterate"})
    public static String mostly;

    static final String base = "This-is-the-base-of-All-key-names-and-is-long".substring(0, length);
    static final String miss = "X-" + base;
    static final List<String> trials = new ArrayList<>();
    static final Random random = new Random();

    @Setup(Level.Trial)
    public void setup()
    {
        int hits = 1;
        int misses = 1;
        switch (mostly)
        {
            case "hits":
                hits = lookups;
                break;
            case "misses":
                misses = lookups;
                break;
            case "iterate":
                hits = lookups / 2;
                misses = lookups - hits;
                break;
            default:
                throw new IllegalStateException();
        }

        for (int h = hits; h-- > 0; )
        {
            trials.add(base + "-" + (h % size));
        }

        for (int m = misses; m-- > 0; )
        {
            trials.add(miss);
        }

        Collections.shuffle(trials);
    }

    static class Pair
    {
        final String key;
        final String value;

        public Pair(String key, String value)
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
        for (int i = 0; i < size - 1; i++)
        {
            String key = base + "-" + i;
            Pair pair = new Pair(key, Long.toString(random.nextLong(), 8));
            fill.put(pair);
        }

        // double up on header 0
        String key = base + "-0";
        Pair pair = new Pair(key, Long.toString(random.nextLong(), 8));
        fill.put(pair);
    }

    private long test(Lookup lookup)
    {
        long result = 0;
        if ("iterate".equals(mostly))
        {
            Iterator<String> t = trials.iterator();
            while (t.hasNext())
            {
                // Look for 4 headers at once because that is what the common case of a
                // ResourceService does
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
                        result ^= p.value.hashCode();
                    else if (two != null && two.equals(k))
                        result ^= p.value.hashCode();
                    else if (three != null && three.equals(k))
                        result ^= p.value.hashCode();
                    else if (four != null && four.equals(k))
                        result ^= p.value.hashCode();
                }
            }
        }
        else
        {
            for (String t : trials)
            {
                Pair p = lookup.get(t);
                if (p != null)
                    result ^= p.value.hashCode();
            }
        }

        return result;
    }

    private long listLookup(List<Pair> list)
    {
        return test(new Lookup()
        {
            @Override
            public Pair get(String k)
            {
                for (int i = 0; i < list.size(); i++)
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
    public long testArrayList() throws Exception
    {
        List<Pair> list = new ArrayList<>(size);
        fill(list::add);
        return listLookup(list);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testLinkedList() throws Exception
    {
        List<Pair> list = new LinkedList<>();
        fill(list::add);
        return listLookup(list);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testLinkedHashMap() throws Exception
    {
        // This loses the true ordering of fields
        Map<String, List<Pair>> map = new LinkedHashMap<>(size);
        fill(p ->
        {
            List<Pair> list = new LinkedList<>();
            list.add(p);
            map.put(p.key.toLowerCase(), list);
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

                return new Iterator<Pair>()
                {
                    Iterator<Pair> current;

                    @Override
                    public boolean hasNext()
                    {
                        if ((current == null || !current.hasNext()) && iter.hasNext())
                            current = iter.next().iterator();
                        return current != null && current.hasNext();
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

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testHashMapAndLinkedList() throws Exception
    {
        // This keeps the true ordering of fields
        Map<String, List<Pair>> map = new HashMap<>(size);
        List<Pair> order = new LinkedList<>();

        fill(p ->
        {
            List<Pair> list = new LinkedList<>();
            list.add(p);
            map.put(p.key.toLowerCase(), list);
            order.add(p);
        });
        return mapLookup(map, order);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testHashMapAndArrayList() throws Exception
    {
        // This keeps the true ordering of fields
        Map<String, List<Pair>> map = new HashMap<>(size);
        List<Pair> order = new ArrayList<>();

        fill(p ->
        {
            List<Pair> list = new ArrayList<>(2);
            list.add(p);
            map.put(p.key.toLowerCase(), list);
            order.add(p);
        });
        return mapLookup(map, order);
    }

    private long mapLookup(Map<String, List<Pair>> map, List<Pair> order)
    {
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
                return order.iterator();
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


