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
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IndexTest
{
    @Test
    public void testImmutableTrieSelection()
    {
        // empty immutable index is always empty
        assertThat(new Index.Builder<String>().build(), instanceOf(EmptyTrie.class));

        // index of ascii characters
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name", "value").build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).with("name", "value").build(), instanceOf(ArrayTrie.class));

        // index of non visible ASCII characters
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name\r\n", "value").build(), instanceOf(ArrayTernaryTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).with("name\r\n", "value").build(), instanceOf(ArrayTernaryTrie.class));

        // large index
        String hugekey = "x".repeat(Character.MAX_VALUE + 1);
        assertTrue(new Index.Builder<String>().caseSensitive(false).with(hugekey, "value").build() instanceof TreeTrie);
        assertTrue(new Index.Builder<String>().caseSensitive(true).with(hugekey, "value").build() instanceof TernaryTrie);
    }

    @Test
    public void testUnlimitdMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().build(), instanceOf(TreeTrie.class));
        assertThat(new Index.Builder<String>().mutable().useVisibleAsciiAlphabet().build(), instanceOf(TreeTrie.class));
        assertThat(new Index.Builder<String>().mutable().useIso8859Alphabet().build(), instanceOf(TreeTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).mutable().useVisibleAsciiAlphabet().build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).mutable().useIso8859Alphabet().build(), instanceOf(TernaryTrie.class));
    }

    @Test
    public void testLimitedMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).build(), instanceOf(ArrayTernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).useVisibleAsciiAlphabet().build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).useIso8859Alphabet().build(), instanceOf(ArrayTernaryTrie.class));

        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).build(), instanceOf(TreeTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).useVisibleAsciiAlphabet().build(), instanceOf(TreeTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).useIso8859Alphabet().build(), instanceOf(TreeTrie.class));

        assertThat(new Index.Builder<String>().caseSensitive(true).mutable().maxCapacity(Character.MAX_VALUE + 1).useVisibleAsciiAlphabet().build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).mutable().maxCapacity(Character.MAX_VALUE + 1).useIso8859Alphabet().build(), instanceOf(TernaryTrie.class));
    }

    @Test
    public void testParseCsv()
    {
        Index<String> index = new Index.Builder<String>()
            .caseSensitive(false)
            .with("aa", "aa")
            .with("AAA", "AAA")
            .with("Alt", "Alt")
            .with("foo", "foo")
            .with("FooBar", "FooBar")
            .build();

        final List<String> list = new ArrayList<>();

        assertTrue(Index.parseCsvIndex(index, "", list::add));
        assertThat(list, empty());

        list.clear();
        assertTrue(Index.parseCsvIndex(index, "aa", list::add));
        assertThat(list, contains("aa"));

        list.clear();
        assertTrue(Index.parseCsvIndex(index, "aaa,alt, foo    , FOOBAR   ", list::add));
        assertThat(list, contains("AAA", "Alt", "foo", "FooBar"));

        list.clear();
        assertTrue(Index.parseCsvIndex(index, "aaa,alt, foo    , FOOBAR   ", t ->
        {
            if (t.length() == 3)
                list.add(t);
            return true;
        }));
        assertThat(list, contains("AAA", "Alt", "foo"));

        list.clear();
        assertFalse(Index.parseCsvIndex(index, "aaa,alt, foo    , FOOBAR   ", t ->
        {
            list.add(t);
            return !"foo".equalsIgnoreCase(t);
        }));
        assertThat(list, contains("AAA", "Alt", "foo"));

        list.clear();
        assertFalse(Index.parseCsvIndex(index, "aaa,alt, unknown, FOOBAR", list::add));
        assertThat(list, contains("AAA", "Alt"));

        list.clear();
        assertFalse(Index.parseCsvIndex(index, "aaa,alt  ,   ", list::add));
        assertThat(list, contains("AAA", "Alt"));

        list.clear();
        assertFalse(Index.parseCsvIndex(index, "aa, aaa, aaaa", list::add));
        assertThat(list, contains("aa", "AAA"));
    }
}
