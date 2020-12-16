//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.util.AbstractTrie.requiredCapacity;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        for (boolean caseSensitive : new boolean[] {true/*, false*/})
        {
            impls.add(new TernaryTrie<Integer>(caseSensitive, 128, 0, 128));
            impls.add(new TernaryTrie<Integer>(caseSensitive, 8, 8, 128));
            impls.add(new ArrayTrie<Integer>(caseSensitive,128));
            impls.add(new ArrayTernaryTrie<Integer>(caseSensitive, 128));
        }
        impls.add(new TreeTrie<>());

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
    public void testFull(AbstractTrie<Integer> trie) throws Exception
    {
        if (!(trie instanceof ArrayTrie<?> || trie instanceof ArrayTernaryTrie<?>))
            return;

        assertFalse(trie.put("Large: This is a really large key and should blow the maximum size of the array trie as lots of nodes should already be used.", 99));
        testGetString(trie);
        testGetBestArray(trie);
        testGetBestBuffer(trie);
    }

    @Test
    public void testRequiredCapacity()
    {
        assertThat(requiredCapacity(Set.of("ABC", "abc"), true, null), is(6));
        assertThat(requiredCapacity(Set.of("ABC", "abc"), false, null), is(3));
        assertThat(requiredCapacity(Set.of(""), false, null), is(0));
        assertThat(requiredCapacity(Set.of("ABC", ""), false, null), is(3));
        assertThat(requiredCapacity(Set.of("ABC"), false, null), is(3));
        assertThat(requiredCapacity(Set.of("ABC", "XYZ"), false, null), is(6));
        assertThat(requiredCapacity(Set.of("A00", "A11"), false, null), is(5));
        assertThat(requiredCapacity(Set.of("A00", "A01", "A10", "A11"), false, null), is(7));
        assertThat(requiredCapacity(Set.of("A", "AB"), false, null), is(2));
        assertThat(requiredCapacity(Set.of("A", "ABC"), false, null), is(3));
        assertThat(requiredCapacity(Set.of("A", "ABCD"), false, null), is(4));
        assertThat(requiredCapacity(Set.of("AB", "ABC"), false, null), is(3));
        assertThat(requiredCapacity(Set.of("ABC", "ABCD"), false, null), is(4));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF"), false, null), is(6));
        assertThat(requiredCapacity(Set.of("AB", "A"), false, null), is(2));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF"), false, null), is(6));
        assertThat(requiredCapacity(Set.of("ABCDEF", "ABC"), false, null), is(6));
        assertThat(requiredCapacity(Set.of("ABC", "ABCDEF", "ABX"), false, null), is(7));
        assertThat(requiredCapacity(Set.of("ABCDEF", "ABC", "ABX"), false, null), is(7));
        assertThat(requiredCapacity(Set.of("ADEF", "AQPR4", "AQZ"), false, null), is(9));
        assertThat(requiredCapacity(Set.of("111", "ADEF", "AQPR4", "AQZ", "999"), false, null), is(15));
        assertThat(requiredCapacity(Set.of("utf-16", "utf-8"), false, null), is(7));
        assertThat(requiredCapacity(Set.of("utf-16", "utf-8", "utf16", "utf8"), false, null), is(10));
        assertThat(requiredCapacity(Set.of("utf-8", "utf8", "utf-16", "utf16", "iso-8859-1", "iso_8859_1"), false, null), is(27));
    }

    @Test
    public void testLargeRequiredCapacity()
    {
        String x = "x".repeat(Character.MAX_VALUE / 2);
        String y = "y".repeat(Character.MAX_VALUE / 2);
        String z = "z".repeat(Character.MAX_VALUE / 2);
        assertThat(requiredCapacity(Set.of(x, y, z), true, null), is(3 * (Character.MAX_VALUE / 2)));
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
        assertThat(trie.getBest("nullxxxx"), nullValue());
    }

    @Test
    @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
    public void testRequiredCapacityAlphabet()
    {
        Set<Character> alphabet = new HashSet<>();

        assertThat(requiredCapacity(Set.of("ABC", "abc"), true, alphabet), is(6));
        assertThat(alphabet, containsInAnyOrder('a', 'b', 'c', 'A', 'B', 'C'));

        alphabet.clear();
        assertThat(requiredCapacity(Set.of("ABCDEF", "abc"), false, alphabet), is(6));
        assertThat(alphabet, containsInAnyOrder('a', 'b', 'c', 'd', 'e', 'f'));

        alphabet.clear();
        String difficult = "!\"%\n\r\u0000\u00a4\u10fb\ufffd";
        assertThat(requiredCapacity(Set.of("ABCDEF", "abc", difficult), false, alphabet), is(15));
        assertThat(alphabet, containsInAnyOrder('a', 'b', 'c', 'd', 'e', 'f', '!','\"', '%', '\r', '\n', '\u00a4', '\u10fb', '\ufffd', '\u0000'));
    }

    @Test
    public void testArrayTrieCapacity()
    {
        ArrayTrie<String> trie = new ArrayTrie<>(Character.MAX_VALUE);
        String huge = "X".repeat(Character.MAX_VALUE);
        assertTrue(trie.put(huge, "wow"));
        assertThat(trie.get(huge), is("wow"));

        assertThrows(IllegalArgumentException.class, () -> new ArrayTrie<String>(Character.MAX_VALUE + 1));
    }
}
