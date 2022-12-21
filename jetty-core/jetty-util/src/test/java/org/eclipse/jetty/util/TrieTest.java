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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.util.AbstractTrie.requiredCapacity;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class TrieTest
{
    private static final String[] KEYS =
    {
        "hello",
        "helloHello",
        "He",
        "HELL",
        "wibble",
        "Wobble",
        "foo-bar",
        "foo+bar",
        "HELL4"
    };
    private static final String[] X_KEYS = Arrays.stream(KEYS).map(s -> "%" + s + "%").toArray(String[]::new);
    private static final String[] NOT_KEYS =
    {
        "h",
        "helloHell",
        "helloHelloHELLO",
        "wibble0",
        "foo_bar",
        "foo-bar-bob",
        "HELL5",
        "\u0000"
    };

    private static final String[] BEST_NOT_KEYS =
    {
        null,
        "hello",
        "helloHello",
        "wibble",
        null,
        "foo-bar",
        "HELL",
        null,
    };

    private static final String[] BEST_NOT_KEYS_LOWER =
    {
        null,
        "hello",
        "hello",
        "wibble",
        null,
        "foo-bar",
        null,
        null,
    };

    private static final String[] X_NOT_KEYS = Arrays.stream(NOT_KEYS).map(s -> "%" + s + "%%%%").toArray(String[]::new);

    public static Stream<Arguments> implementations()
    {
        List<AbstractTrie<Integer>> impls = new ArrayList<>();

        for (boolean caseSensitive : new boolean[] {true, false})
        {
            impls.add(new ArrayTrie<Integer>(caseSensitive, 128));
            impls.add(new ArrayTernaryTrie<Integer>(caseSensitive, 128));
            impls.add(new TreeTrie<>(caseSensitive));
        }

        for (AbstractTrie<Integer> trie : impls)
        {
            for (int i = 0; i < KEYS.length; i++)
            {
                if (!trie.put(KEYS[i], i))
                    throw new IllegalStateException();
            }
        }

        return impls.stream().map(Arguments::of);
    }

    public static Stream<Arguments> emptyImplementations()
    {
        List<AbstractTrie<Integer>> impls = new ArrayList<>();

        for (boolean caseSensitive : new boolean[] {true, false})
        {
            impls.add(new ArrayTrie<Integer>(caseSensitive, 128));
            impls.add(new ArrayTernaryTrie<Integer>(caseSensitive, 128));
            impls.add(new TreeTrie<>(caseSensitive));
        }

        return impls.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testOverflow(AbstractTrie<Integer> trie) throws Exception
    {
        int i = 0;
        while (true)
        {
            if (++i > 10000)
                break; // must not be fixed size
            if (!trie.put("prefix" + i, i))
            {
                String key = "prefix" + i;

                // Assert that all keys can be gotten.
                for (String k : trie.keySet())
                {
                    assertNotNull(trie.get(k));
                    assertNotNull(trie.get(toAsciiDirectByteBuffer(k, 0))); // has to be a direct buffer
                    assertNull(trie.get(toAsciiDirectByteBuffer(k, k.length()))); // has to be a direct buffer
                }

                // Assert that all getBest() variants do work on full tries.
                assertNotNull(trie.getBest(key), "key=" + key);
                assertNotNull(trie.getBest(key.getBytes(StandardCharsets.US_ASCII), 0, key.length()), "key=" + key);
                assertNotNull(trie.getBest(toAsciiDirectByteBuffer(key, 0), 0, key.length()), "key=" + key); // has to be a direct buffer
                assertNull(trie.getBest(toAsciiDirectByteBuffer(key, key.length()), 0, key.length()), "key=" + key);  // has to be a direct buffer
                break;
            }
        }

        if (trie instanceof ArrayTrie || trie instanceof ArrayTernaryTrie)
            assertFalse(trie.put("overflow", 0));
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
    public void testKeySet(AbstractTrie<Integer> trie) throws Exception
    {
        for (String value : KEYS)
            assertThat(value, trie.keySet().contains(value), is(true));
        for (String value : NOT_KEYS)
            assertThat(value, trie.keySet().contains(value), is(false));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetString(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(KEYS[i]), is(i));
        for (int i = 0; i < NOT_KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(NOT_KEYS[i]), nullValue());
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = KEYS[i].toLowerCase();
            Integer actual = trie.get(k);
            if (k.equals(KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = KEYS[i].toUpperCase();
            Integer actual = trie.get(k);
            if (k.equals(KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBuffer(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(BufferUtil.toBuffer(X_KEYS[i]), 1, KEYS[i].length()), is(i));
        for (int i = 0; i < NOT_KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(BufferUtil.toBuffer(X_NOT_KEYS[i]), 1, NOT_KEYS[i].length()), nullValue());
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = X_KEYS[i].toLowerCase();
            Integer actual = trie.get(BufferUtil.toBuffer(k), 1, KEYS[i].length());
            if (k.equals(X_KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = X_KEYS[i].toUpperCase();
            Integer actual = trie.get(BufferUtil.toBuffer(k), 1, KEYS[i].length());
            if (k.equals(X_KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetDirectBuffer(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(BufferUtil.toDirectBuffer(X_KEYS[i]), 1, KEYS[i].length()), is(i));
        for (int i = 0; i < NOT_KEYS.length; i++)
            assertThat(Integer.toString(i), trie.get(BufferUtil.toDirectBuffer(X_NOT_KEYS[i]), 1, NOT_KEYS[i].length()), nullValue());
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = X_KEYS[i].toLowerCase();
            Integer actual = trie.get(BufferUtil.toDirectBuffer(k), 1, KEYS[i].length());
            if (k.equals(X_KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
        for (int i = 0; i < KEYS.length; i++)
        {
            String k = X_KEYS[i].toUpperCase();
            Integer actual = trie.get(BufferUtil.toDirectBuffer(k), 1, KEYS[i].length());
            if (k.equals(X_KEYS[i]) || trie.isCaseInsensitive())
                assertThat(k, actual, is(i));
            else
                assertThat(k, actual, nullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestArray(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i];
            Integer actual = trie.getBest(StringUtil.getUtf8Bytes(k), 1, X_NOT_KEYS[i].length() - 1);
            Integer expected = BEST_NOT_KEYS[i] == null ? null : trie.get(BEST_NOT_KEYS[i]);
            assertThat(k, actual, is(expected));
        }

        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i].toLowerCase();
            Integer actual = trie.getBest(StringUtil.getUtf8Bytes(k), 1, X_NOT_KEYS[i].length() - 1);
            String[] expectations = trie.isCaseSensitive() ? BEST_NOT_KEYS_LOWER : BEST_NOT_KEYS;
            Integer expected = expectations[i] == null ? null : trie.get(expectations[i]);
            assertThat(k, actual, is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestBuffer(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i];
            Integer actual = trie.getBest(BufferUtil.toBuffer(k), 1, X_NOT_KEYS[i].length() - 1);
            Integer expected = BEST_NOT_KEYS[i] == null ? null : trie.get(BEST_NOT_KEYS[i]);
            assertThat(k, actual, is(expected));
        }

        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i].toLowerCase();
            Integer actual = trie.getBest(BufferUtil.toBuffer(k), 1, X_NOT_KEYS[i].length() - 1);
            String[] expectations = trie.isCaseSensitive() ? BEST_NOT_KEYS_LOWER : BEST_NOT_KEYS;
            Integer expected = expectations[i] == null ? null : trie.get(expectations[i]);
            assertThat(k, actual, is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testGetBestDirectBuffer(AbstractTrie<Integer> trie) throws Exception
    {
        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i];
            Integer actual = trie.getBest(BufferUtil.toDirectBuffer(k), 1, X_NOT_KEYS[i].length() - 1);
            Integer expected = BEST_NOT_KEYS[i] == null ? null : trie.get(BEST_NOT_KEYS[i]);
            assertThat(k, actual, is(expected));
        }

        for (int i = 0; i < NOT_KEYS.length; i++)
        {
            String k = X_NOT_KEYS[i].toLowerCase();
            Integer actual = trie.getBest(BufferUtil.toDirectBuffer(k), 1, X_NOT_KEYS[i].length() - 1);
            String[] expectations = trie.isCaseSensitive() ? BEST_NOT_KEYS_LOWER : BEST_NOT_KEYS;
            Integer expected = expectations[i] == null ? null : trie.get(expectations[i]);
            assertThat(k, actual, is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testOtherChars(AbstractTrie<Integer> trie) throws Exception
    {
        Assumptions.assumeTrue(trie instanceof ArrayTrie<?> || trie instanceof TreeTrie);
        assertTrue(trie.put("8859:ä", -1));
        assertTrue(trie.put("inv:\r\n", -2));
        assertTrue(trie.put("utf:\u20ac", -3));

        assertThat(trie.getBest("8859:äxxxxx"), is(-1));
        assertThat(trie.getBest("inv:\r\n:xxxx"), is(-2));
        assertThat(trie.getBest("utf:\u20ac"), is(-3));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testFull(AbstractTrie<Integer> trie) throws Exception
    {
        if (!(trie instanceof ArrayTrie<?> || trie instanceof ArrayTernaryTrie<?>))
            return;

        assertFalse(trie.put("Large: This is a really large key and should blow the maximum size of the array trie as lots of nodes should already be used.", 99));
        testGetString(trie);
        testGetBestArray(trie);
        testGetBestBuffer(trie);
        assertNull(trie.getBest("Large: This is a really large key and should blow the maximum size of the array trie as lots of nodes should already be used."));
    }

    @Test
    public void testRequiredCapacity()
    {
        assertThat(requiredCapacity(Set.of("ABC", "abc"), true), is(1 + 6));
        assertThat(requiredCapacity(Set.of("ABC", "abc"), false), is(1 + 3));
        assertThat(requiredCapacity(Set.of(""), false), is(1 + 0));
        assertThat(requiredCapacity(Set.of("ABC", ""), false), is(1 + 3));
        assertThat(requiredCapacity(Set.of("ABC"), false), is(1 + 3));
        assertThat(requiredCapacity(Set.of("ABC", "XYZ"), false), is(1 + 6));
        assertThat(requiredCapacity(Set.of("A00", "A11"), false), is(1 + 5));
        assertThat(requiredCapacity(Set.of("A00", "A01", "A10", "A11"), false), is(1 + 7));
        assertThat(requiredCapacity(Set.of("A", "AB"), false), is(1 + 2));
        assertThat(requiredCapacity(Set.of("A", "ABC"), false), is(1 + 3));
        assertThat(requiredCapacity(Set.of("A", "ABCD"), false), is(1 + 4));
        assertThat(requiredCapacity(Set.of("AB", "ABC"), false), is(1 + 3));
        assertThat(requiredCapacity(Set.of("ABC", "ABCD"), false), is(1 + 4));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF"), false), is(1 + 6));
        assertThat(requiredCapacity(Set.of("AB", "A"), false), is(1 + 2));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF"), false), is(1 + 6));
        assertThat(requiredCapacity(Set.of("ABCDEF", "ABC"), false), is(1 + 6));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF", "ABX"), false), is(1 + 7));
        assertThat(requiredCapacity(Set.of("ABCDEF", "ABC", "ABX"), false), is(1 + 7));
        assertThat(requiredCapacity(Set.of("ADEF", "AQPR4", "AQZ"), false), is(1 + 9));
        assertThat(requiredCapacity(Set.of("111", "ADEF", "AQPR4", "AQZ", "999"), false), is(1 + 15));
        assertThat(requiredCapacity(Set.of("utf-16", "utf-8"), false), is(1 + 7));
        assertThat(requiredCapacity(Set.of("utf-16", "utf-8", "utf16", "utf8"), false), is(1 + 10));
        assertThat(requiredCapacity(Set.of("utf-8", "utf8", "utf-16", "utf16", "iso-8859-1", "iso_8859_1"), false), is(1 + 27));
    }

    @Test
    public void testLargeRequiredCapacity()
    {
        String x = "x".repeat(Character.MAX_VALUE / 2);
        String y = "y".repeat(Character.MAX_VALUE / 2);
        String z = "z".repeat(Character.MAX_VALUE / 2);
        assertThat(requiredCapacity(Set.of(x, y, z), true), is(1 + 3 * (Character.MAX_VALUE / 2)));
    }

    @ParameterizedTest
    @MethodSource("emptyImplementations")
    public void testIsEmpty(AbstractTrie<Integer> trie) throws Exception
    {
        assertTrue(trie.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testIsNotEmpty(AbstractTrie<Integer> trie) throws Exception
    {
        assertFalse(trie.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testEmptyKey(AbstractTrie<Integer> trie) throws Exception
    {
        assertTrue(trie.put("", -1));
        assertThat(trie.get(""), is(-1));
        assertThat(trie.getBest(""), is(-1));
        assertThat(trie.getBest("anything"), is(-1));
        assertThat(trie.getBest(BufferUtil.toBuffer("")), is(-1));
        assertThat(trie.getBest(BufferUtil.toBuffer("anything")), is(-1));
        assertThat(trie.getBest(BufferUtil.toBuffer("").array()), is(-1));
        assertThat(trie.getBest(BufferUtil.toBuffer("anything").array()), is(-1));

        for (int i = 0; i < KEYS.length; i++)
        {
            assertThat(trie.get(KEYS[i]), is(i));
            assertThat(trie.getBest(KEYS[i] + "XYZ"), is(i));
            assertThat(trie.getBest(BufferUtil.toBuffer(KEYS[i] + "XYZ")), is(i));
            assertThat(trie.getBest(BufferUtil.toBuffer(KEYS[i] + "XYZ").array()), is(i));
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testNullKey(AbstractTrie<Integer> trie) throws Exception
    {
        assertThrows(NullPointerException.class, () -> trie.put(null, -1));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testNullValue(AbstractTrie<Integer> trie) throws Exception
    {
        trie.put("null", 0);
        assertTrue(trie.put("null", null));
        assertThat(trie.get("null"), nullValue());
        assertThat(trie.getBest("null;xxxx"), nullValue());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testNullChar(AbstractTrie<Integer> trie) throws Exception
    {
        String key = "A" + ((char)0) + "c";
        trie.put(key, 103);
        assertThat(trie.get(key), is(103));
        assertThat(trie.getBest(key + ";xxxx"), is(103));
    }

    @ParameterizedTest
    @MethodSource("emptyImplementations")
    public void testHttp(AbstractTrie<Integer> trie)
    {
        trie.put("Host:", 1);
        trie.put("Host: name", 2);

        assertThat(trie.getBest("Other: header\r\n"), nullValue());
        assertThat(trie.getBest("Host: other\r\n"), is(1));
        assertThat(trie.getBest("Host: name\r\n"), is(2));
        if (trie.isCaseInsensitive())
            assertThat(trie.getBest("HoSt: nAme\r\n"), is(2));
        else
            assertThat(trie.getBest("HoSt: nAme\r\n"), nullValue());

        assertThat(trie.getBest(BufferUtil.toBuffer("Other: header\r\n")), nullValue());
        assertThat(trie.getBest(BufferUtil.toBuffer("Host: other\r\n")), is(1));
        assertThat(trie.getBest(BufferUtil.toBuffer("Host: name\r\n")), is(2));
        if (trie.isCaseInsensitive())
            assertThat(trie.getBest(BufferUtil.toBuffer("HoSt: nAme\r\n")), is(2));
        else
            assertThat(trie.getBest(BufferUtil.toBuffer("HoSt: nAme\r\n")), nullValue());

        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Other: header\r\n")), nullValue());
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Host: other\r\n")), is(1));
        assertThat(trie.getBest(BufferUtil.toDirectBuffer("Host: name\r\n")), is(2));
        if (trie.isCaseInsensitive())
            assertThat(trie.getBest(BufferUtil.toDirectBuffer("HoSt: nAme\r\n")), is(2));
        else
            assertThat(trie.getBest(BufferUtil.toDirectBuffer("HoSt: nAme\r\n")), nullValue());
    }

    @Test
    public void testArrayTrieCapacity()
    {
        ArrayTrie<String> trie = new ArrayTrie<>(Character.MAX_VALUE);
        String huge = "x".repeat(Character.MAX_VALUE - 1);
        assertTrue(trie.put(huge, "wow"));
        assertThat(trie.get(huge), is("wow"));

        assertThrows(IllegalArgumentException.class, () -> new ArrayTrie<String>(Character.MAX_VALUE + 1));

        assertThat(trie.keySet(), contains(huge));
        assertThat(trie.toString(), containsString(huge));
    }
}
