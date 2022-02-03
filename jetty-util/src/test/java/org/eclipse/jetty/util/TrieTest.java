//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrieTest
{
    public static Stream<Arguments> implementations()
    {
        List<Trie<Integer>> impls = new ArrayList<>();

        impls.add(new ArrayTrie<Integer>(128));
        impls.add(new TreeTrie<Integer>());
        impls.add(new ArrayTernaryTrie<Integer>(128));

        for (Trie<Integer> trie : impls)
        {
            trie.put("hello", 1);
            trie.put("He", 2);
            trie.put("HELL", 3);
            trie.put("wibble", 4);
            trie.put("Wobble", 5);
            trie.put("foo-bar", 6);
            trie.put("foo+bar", 7);
            trie.put("HELL4", 8);
            trie.put("", 9);
        }

        return impls.stream().map(Arguments::of);
    }

    public static Stream<Arguments> trieConstructors()
    {
        List<Function<Integer, Trie<?>>> tries = new ArrayList<>();
        tries.add(ArrayTrie::new);
        tries.add(ArrayTernaryTrie::new);
        tries.add(capacity -> new ArrayTernaryTrie.Growing<>(capacity, capacity));
        return tries.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testOverflow(Trie<Integer> trie) throws Exception
    {
        int i = 0;
        while (true)
        {
            if (++i > 10000)
                break; // must not be fixed size
            if (!trie.put("prefix" + i, i))
            {
                assertTrue(trie.isFull());
                String key = "prefix" + i;

                // Assert that all keys can be gotten.
                for (String k : trie.keySet())
                {
                    assertNotNull(trie.get(k));
                    assertNotNull(trie.get(toAsciiDirectByteBuffer(k, 0))); // has to be a direct buffer
                    assertEquals(9, trie.get(toAsciiDirectByteBuffer(k, k.length()))); // has to be a direct buffer
                }

                // Assert that all getBest() variants do work on full tries.
                assertNotNull(trie.getBest(key), "key=" + key);
                assertNotNull(trie.getBest(key.getBytes(StandardCharsets.US_ASCII), 0, key.length()), "key=" + key);
                assertNotNull(trie.getBest(toAsciiDirectByteBuffer(key, 0), 0, key.length()), "key=" + key); // has to be a direct buffer
                assertNull(trie.getBest(toAsciiDirectByteBuffer(key, key.length()), 0, key.length()), "key=" + key);  // has to be a direct buffer
                break;
            }
        }

        assertTrue(!trie.isFull() || !trie.put("overflow", 0));
    }

    private static ByteBuffer toAsciiDirectByteBuffer(String s, int pos)
    {
        ByteBuffer bb = ByteBuffer.allocateDirect(s.length());
        bb.put(s.getBytes(StandardCharsets.US_ASCII));
        bb.position(pos);
        return bb;
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testKeySet(Trie<Integer> trie) throws Exception
    {
        String[] values = new String[]{
            "hello",
            "He",
            "HELL",
            "wibble",
            "Wobble",
            "foo-bar",
            "foo+bar",
            "HELL4",
            ""
        };

        for (String value : values)
        {
            assertThat(value, is(in(trie.keySet())));
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetString(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.get("hello").intValue());
        assertEquals(2, trie.get("He").intValue());
        assertEquals(3, trie.get("HELL").intValue());
        assertEquals(4, trie.get("wibble").intValue());
        assertEquals(5, trie.get("Wobble").intValue());
        assertEquals(6, trie.get("foo-bar").intValue());
        assertEquals(7, trie.get("foo+bar").intValue());

        assertEquals(1, trie.get("Hello").intValue());
        assertEquals(2, trie.get("HE").intValue());
        assertEquals(3, trie.get("heLL").intValue());
        assertEquals(4, trie.get("Wibble").intValue());
        assertEquals(5, trie.get("wobble").intValue());
        assertEquals(6, trie.get("Foo-bar").intValue());
        assertEquals(7, trie.get("FOO+bar").intValue());
        assertEquals(8, trie.get("HELL4").intValue());
        assertEquals(9, trie.get("").intValue());

        assertEquals(null, trie.get("helloworld"));
        assertEquals(null, trie.get("Help"));
        assertEquals(null, trie.get("Blah"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBuffer(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.get(BufferUtil.toBuffer("xhellox"), 1, 5).intValue());
        assertEquals(2, trie.get(BufferUtil.toBuffer("xhellox"), 1, 2).intValue());
        assertEquals(3, trie.get(BufferUtil.toBuffer("xhellox"), 1, 4).intValue());
        assertEquals(4, trie.get(BufferUtil.toBuffer("wibble"), 0, 6).intValue());
        assertEquals(5, trie.get(BufferUtil.toBuffer("xWobble"), 1, 6).intValue());
        assertEquals(6, trie.get(BufferUtil.toBuffer("xfoo-barx"), 1, 7).intValue());
        assertEquals(7, trie.get(BufferUtil.toBuffer("xfoo+barx"), 1, 7).intValue());

        assertEquals(1, trie.get(BufferUtil.toBuffer("xhellox"), 1, 5).intValue());
        assertEquals(2, trie.get(BufferUtil.toBuffer("xHELLox"), 1, 2).intValue());
        assertEquals(3, trie.get(BufferUtil.toBuffer("xhellox"), 1, 4).intValue());
        assertEquals(4, trie.get(BufferUtil.toBuffer("Wibble"), 0, 6).intValue());
        assertEquals(5, trie.get(BufferUtil.toBuffer("xwobble"), 1, 6).intValue());
        assertEquals(6, trie.get(BufferUtil.toBuffer("xFOO-barx"), 1, 7).intValue());
        assertEquals(7, trie.get(BufferUtil.toBuffer("xFOO+barx"), 1, 7).intValue());

        assertEquals(null, trie.get(BufferUtil.toBuffer("xHelloworldx"), 1, 10));
        assertEquals(null, trie.get(BufferUtil.toBuffer("xHelpx"), 1, 4));
        assertEquals(null, trie.get(BufferUtil.toBuffer("xBlahx"), 1, 4));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetDirectBuffer(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.get(BufferUtil.toDirectBuffer("xhellox"), 1, 5).intValue());
        assertEquals(2, trie.get(BufferUtil.toDirectBuffer("xhellox"), 1, 2).intValue());
        assertEquals(3, trie.get(BufferUtil.toDirectBuffer("xhellox"), 1, 4).intValue());
        assertEquals(4, trie.get(BufferUtil.toDirectBuffer("wibble"), 0, 6).intValue());
        assertEquals(5, trie.get(BufferUtil.toDirectBuffer("xWobble"), 1, 6).intValue());
        assertEquals(6, trie.get(BufferUtil.toDirectBuffer("xfoo-barx"), 1, 7).intValue());
        assertEquals(7, trie.get(BufferUtil.toDirectBuffer("xfoo+barx"), 1, 7).intValue());

        assertEquals(1, trie.get(BufferUtil.toDirectBuffer("xhellox"), 1, 5).intValue());
        assertEquals(2, trie.get(BufferUtil.toDirectBuffer("xHELLox"), 1, 2).intValue());
        assertEquals(3, trie.get(BufferUtil.toDirectBuffer("xhellox"), 1, 4).intValue());
        assertEquals(4, trie.get(BufferUtil.toDirectBuffer("Wibble"), 0, 6).intValue());
        assertEquals(5, trie.get(BufferUtil.toDirectBuffer("xwobble"), 1, 6).intValue());
        assertEquals(6, trie.get(BufferUtil.toDirectBuffer("xFOO-barx"), 1, 7).intValue());
        assertEquals(7, trie.get(BufferUtil.toDirectBuffer("xFOO+barx"), 1, 7).intValue());

        assertEquals(null, trie.get(BufferUtil.toDirectBuffer("xHelloworldx"), 1, 10));
        assertEquals(null, trie.get(BufferUtil.toDirectBuffer("xHelpx"), 1, 4));
        assertEquals(null, trie.get(BufferUtil.toDirectBuffer("xBlahx"), 1, 4));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestArray(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.getBest(StringUtil.getUtf8Bytes("xhelloxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(StringUtil.getUtf8Bytes("xhelxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(StringUtil.getUtf8Bytes("xhellxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(StringUtil.getUtf8Bytes("xfoo-barxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(StringUtil.getUtf8Bytes("xhell4xxxx"), 1, 8).intValue());

        assertEquals(1, trie.getBest(StringUtil.getUtf8Bytes("xHELLOxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(StringUtil.getUtf8Bytes("xHELxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(StringUtil.getUtf8Bytes("xHELLxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(StringUtil.getUtf8Bytes("xfoo-BARxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(StringUtil.getUtf8Bytes("xHELL4xxxx"), 1, 8).intValue());
        assertEquals(9, trie.getBest(StringUtil.getUtf8Bytes("xZZZZZxxxx"), 1, 8).intValue());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestBuffer(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.getBest(BufferUtil.toBuffer("xhelloxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(BufferUtil.toBuffer("xhelxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(BufferUtil.toBuffer("xhellxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(BufferUtil.toBuffer("xfoo-barxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(BufferUtil.toBuffer("xhell4xxxx"), 1, 8).intValue());

        assertEquals(1, trie.getBest(BufferUtil.toBuffer("xHELLOxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(BufferUtil.toBuffer("xHELxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(BufferUtil.toBuffer("xHELLxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(BufferUtil.toBuffer("xfoo-BARxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(BufferUtil.toBuffer("xHELL4xxxx"), 1, 8).intValue());
        assertEquals(9, trie.getBest(BufferUtil.toBuffer("xZZZZZxxxx"), 1, 8).intValue());

        ByteBuffer buffer = (ByteBuffer)BufferUtil.toBuffer("xhelloxxxxxxx").position(2);
        assertEquals(1, trie.getBest(buffer, -1, 10).intValue());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestDirectBuffer(Trie<Integer> trie) throws Exception
    {
        assertEquals(1, trie.getBest(BufferUtil.toDirectBuffer("xhelloxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(BufferUtil.toDirectBuffer("xhelxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(BufferUtil.toDirectBuffer("xhellxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(BufferUtil.toDirectBuffer("xfoo-barxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(BufferUtil.toDirectBuffer("xhell4xxxx"), 1, 8).intValue());

        assertEquals(1, trie.getBest(BufferUtil.toDirectBuffer("xHELLOxxxx"), 1, 8).intValue());
        assertEquals(2, trie.getBest(BufferUtil.toDirectBuffer("xHELxoxxxx"), 1, 8).intValue());
        assertEquals(3, trie.getBest(BufferUtil.toDirectBuffer("xHELLxxxxx"), 1, 8).intValue());
        assertEquals(6, trie.getBest(BufferUtil.toDirectBuffer("xfoo-BARxx"), 1, 8).intValue());
        assertEquals(8, trie.getBest(BufferUtil.toDirectBuffer("xHELL4xxxx"), 1, 8).intValue());
        assertEquals(9, trie.getBest(BufferUtil.toDirectBuffer("xZZZZZxxxx"), 1, 8).intValue());

        ByteBuffer buffer = (ByteBuffer)BufferUtil.toDirectBuffer("xhelloxxxxxxx").position(2);
        assertEquals(1, trie.getBest(buffer, -1, 10).intValue());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testFull(Trie<Integer> trie) throws Exception
    {
        if (!(trie instanceof ArrayTrie<?> || trie instanceof ArrayTernaryTrie<?>))
            return;

        assertFalse(trie.put("Large: This is a really large key and should blow the maximum size of the array trie as lots of nodes should already be used.", 99));
        testGetString(trie);
        testGetBestArray(trie);
        testGetBestBuffer(trie);
    }

    @ParameterizedTest
    @MethodSource("trieConstructors")
    public void testTrieCapacityOverflow(Function<Integer, Trie<?>> constructor)
    {
        assertThrows(IllegalArgumentException.class, () -> constructor.apply((int)Character.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("trieConstructors")
    public void testTrieCapacity(Function<Integer, Trie<String>> constructor)
    {
        Trie<String> trie = constructor.apply(Character.MAX_VALUE - 1);

        char[] c1 = new char[Character.MAX_VALUE - 1];
        Arrays.fill(c1, 'a');
        String huge = new String(c1);
        assertTrue(trie.put(huge, "wow"));
        assertThat(trie.get(huge), is("wow"));
    }

    @ParameterizedTest
    @MethodSource("trieConstructors")
    public void testTrieOverflowReject(Function<Integer, Trie<String>> constructor)
    {
        Trie<String> trie = constructor.apply(Character.MAX_VALUE - 1);
        assertTrue(trie.put("X", "/"));
        assertThat(trie.getBest("X", 0, 1), is("/"));

        char[] c1 = new char[Character.MAX_VALUE - 1];
        Arrays.fill(c1, 'a');
        String huge = new String(c1);
        assertFalse(trie.put(huge, "overflow"));
        assertNull(trie.get(huge));

        // The previous entry was not overridden
        assertThat(trie.getBest("X", 0, 1), is("/"));
    }

    @ParameterizedTest
    @MethodSource("trieConstructors")
    public void testHttp(Function<Integer, Trie<String>> constructor)
    {
        Trie<String> trie = constructor.apply(500);
        trie.put("Host:", "H");
        trie.put("Host: name", "HF");

        assertThat(trie.getBest("Other: header\r\n"), nullValue());
        assertThat(trie.getBest("Host: other\r\n"), is("H"));
        assertThat(trie.getBest("Host: name\r\n"), is("HF"));
        assertThat(trie.getBest("HoSt: nAme\r\n"), is("HF"));

        assertThat(trie.getBest(BufferUtil.toBuffer("Other: header\r\n")), nullValue());
        assertThat(trie.getBest(BufferUtil.toBuffer("Host: other\r\n")), is("H"));
        assertThat(trie.getBest(BufferUtil.toBuffer("Host: name\r\n")), is("HF"));
        assertThat(trie.getBest(BufferUtil.toBuffer("HoSt: nAme\r\n")), is("HF"));

        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Other: header\r\n")), nullValue());
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Host: other\r\n")), is("H"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Host: name\r\n")), is("HF"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("HoSt: nAme\r\n")), is("HF"));
    }

    @ParameterizedTest
    @MethodSource("trieConstructors")
    public void testEmptyKey(Function<Integer, Trie<String>> constructor)
    {
        Trie<String> trie = constructor.apply(500);
        assertTrue(trie.put("", "empty"));
        assertTrue(trie.put("abc", "prefixed"));

        assertThat(trie.getBest("unknown"), is("empty"));
        assertThat(trie.getBest("a"), is("empty"));
        assertThat(trie.getBest("aX"), is("empty"));
        assertThat(trie.getBest("abc"), is("prefixed"));
        assertThat(trie.getBest("abcd"), is("prefixed"));

        assertThat(trie.getBest(BufferUtil.toBuffer("unknown")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toBuffer("a")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toBuffer("aX")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toBuffer("abc")), is("prefixed"));
        assertThat(trie.getBest(BufferUtil.toBuffer("abcd")), is("prefixed"));

        assertThat(trie.getBest(BufferUtil.toDirectBuffer("unknown")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("a")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("aX")), is("empty"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("abc")), is("prefixed"));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("abcd")), is("prefixed"));
    }

    @Test
    public void testArrayTernaryTrieSize()
    {
        ArrayTernaryTrie<String> trie = new ArrayTernaryTrie<>();

        assertTrue(trie.put("abc", "X"));
        assertTrue(trie.put("def", "Y"));
        assertTrue(trie.put("dee", "Z"));

        assertEquals(3, trie.size());
    }

    @Test
    public void testArrayTernaryTrieKeySet()
    {
        ArrayTernaryTrie<String> trie = new ArrayTernaryTrie<>();

        assertTrue(trie.put("abc", "X"));
        assertTrue(trie.put("def", "Y"));
        assertTrue(trie.put("dee", "Z"));

        Set<String> keys = trie.keySet();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("abc"));
        assertTrue(keys.contains("def"));
        assertTrue(keys.contains("dee"));
    }

    @Test
    public void testArrayTernaryTrieEntrySet()
    {
        ArrayTernaryTrie<String> trie = new ArrayTernaryTrie<>();

        assertTrue(trie.put("abc", "X"));
        assertTrue(trie.put("def", "Y"));
        assertTrue(trie.put("dee", "Z"));

        Set<Map.Entry<String, String>> entries = trie.entrySet();
        assertEquals(3, entries.size());
        Set<String> keys = entries.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        assertTrue(keys.contains("abc"));
        assertTrue(keys.contains("def"));
        assertTrue(keys.contains("dee"));
        Set<String> values = entries.stream().map(Map.Entry::getValue).collect(Collectors.toSet());
        assertTrue(values.contains("X"));
        assertTrue(values.contains("Y"));
        assertTrue(values.contains("Z"));
    }
}
