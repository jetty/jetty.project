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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
public class TrieBenchmark
{
    @Param({
        "ArrayTrie",
        "TernaryTrie",
        "ArrayTernaryTrie",
        "TreeTrie",
        "HashTrie",
    })
    public static String TRIE_TYPE;

    private AbstractTrie<String> trie;

    private static final String LONG_HIT = "This-is-a-Moderately-Long-Key-that-will-hit";
    private static final String LONG_MISS = "This-is-a-Moderately-Long-Key-that-will-miss";

    @Setup
    public void setUp() throws Exception
    {
        boolean caseSensitive = false;
        Set<Character> alphabet = new HashSet<>();
        int capacity = 4096;

        switch (TRIE_TYPE)
        {
            case "ArrayTrie":
                trie = new ArrayTrie<>(caseSensitive, capacity);
                break;
            case "ArrayTernaryTrie":
                trie = new ArrayTernaryTrie<>(caseSensitive, capacity);
                break;
            case "TreeTrie":
                trie = new TreeTrie();
                break;
            case "HashTrie":
                trie = new HashTrie(caseSensitive);
                break;
            default:
                throw new AssertionError("No trie for " + TRIE_TYPE);
        }

        for (String k : HttpParser.CACHE.keySet())
            if (!trie.put(k, HttpParser.CACHE.get(k).toString()))
                throw new IllegalStateException("Could not add " + k);

        trie.put(LONG_HIT, LONG_HIT);

//        System.err.println("====");
//        for (String k : trie.keySet())
//            System.err.printf("%s: %s%n", k, trie.get(k));
//        System.err.println("----");
    }

    @Benchmark
    public boolean testPut()
    {
        trie.clear();
        for (String k : HttpParser.CACHE.keySet())
            if (!trie.put(k, HttpParser.CACHE.get(k).toString()))
                return false;
        return true;
    }

    @Benchmark
    public boolean testGet()
    {
        if (
            // short miss
            trie.get("Xx") == null &&
                // long miss
                trie.get("Zasdfadsfasfasfbae9mn3m0mdmmfkk092nvfs0smnsmm3k23m3m23m") == null &&

                // short near miss
                trie.get("Pragma: no-cache0") == null &&

                // long near miss
                trie.get(LONG_MISS) == null &&

                // short hit
                trie.get("Pragma: no-cache") != null &&

                // medium hit
                trie.get("Accept-Language: en-US,enq=0.5") != null &&

                // long hit
                trie.get(LONG_HIT) != null
        )
            return true;

        throw new IllegalStateException();
    }

    private static final ByteBuffer X = BufferUtil.toBuffer("Xx\r\nxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final ByteBuffer Z = BufferUtil.toBuffer("Zasdfadsfasfasfbae9mn3m0mdmmfkk092nvfs0smnsmm3k23m3m23m\r\nxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final ByteBuffer M = BufferUtil.toBuffer(LONG_MISS + ";xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final ByteBuffer P = BufferUtil.toBuffer("Pragma: no-cache;xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final ByteBuffer A = BufferUtil.toBuffer("Accept-Language: en-US,enq=0.5;xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final ByteBuffer H = BufferUtil.toBuffer(LONG_HIT + ";xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

    @Benchmark
    public boolean testGetBest()
    {
        if (
            // short miss
            trie.getBest(X) == null &&

                // long miss
                trie.getBest(Z) == null &&

                // long near miss
                trie.getBest(M) == null &&

                // short hit
                trie.getBest(P) != null &&

                // medium hit
                trie.getBest(A) != null &&

                // long hit
                trie.getBest(H) != null)
            return true;

        throw new IllegalStateException();
    }

    private class HashTrie extends AbstractTrie<String>
    {
        Map<String, String> _contents;

        public HashTrie(boolean caseSensitive)
        {
            super(caseSensitive);
            _contents = new HashMap<>();
        }

        @Override
        public String get(String s)
        {
            if (isCaseInsensitive())
                s = StringUtil.asciiToLowerCase(s);
            return _contents.get(s);
        }

        @Override
        public String get(String s, int offset, int len)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String get(ByteBuffer b, int offset, int len)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBest(String s, int offset, int len)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBest(ByteBuffer b, int offset, int len)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBest(ByteBuffer buf)
        {
            int len = buf.remaining();
            for (int i = 0; i < len; i++)
            {
                byte b = buf.get(buf.position() + i);
                switch (b)
                {
                    case '\r':
                    case '\n':
                    case ';':
                        String s = BufferUtil.toString(buf, buf.position(), i, StandardCharsets.ISO_8859_1);
                        if (isCaseInsensitive())
                            s = StringUtil.asciiToLowerCase(s);
                        return trie.get(s);
                    default:
                }
            }
            throw new IllegalStateException();
        }

        @Override
        public boolean isEmpty()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> keySet()
        {
            return _contents.keySet();
        }

        @Override
        public boolean put(String s, String v)
        {
            if (isCaseInsensitive())
                s = StringUtil.asciiToLowerCase(s);
            return _contents.put(s, v) == null;
        }

        @Override
        public void clear()
        {
            _contents.clear();
        }
    }

//    public static void main(String... args) throws Exception
//    {
//        TrieBenchmark.TRIE_TYPE = "HashTrie";
//        TrieBenchmark b = new TrieBenchmark();
//        b.setUp();
//        b.testGet();
//        b.testGetBest();
//        b.testPut();
//    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(TrieBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .warmupTime(TimeValue.seconds(5))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(5))
            .forks(1)
            .threads(1)
            .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }

}
