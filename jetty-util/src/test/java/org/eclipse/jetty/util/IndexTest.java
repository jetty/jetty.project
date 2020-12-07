//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IndexTest
{
    @Test
    public void testImmutableTrieSelection()
    {
        // empty immutable index is always empty
        assertThat(new Index.Builder<String>().build(), instanceOf(EmptyTrie.class));

        // index of ascii characters uses ArrayTrie
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name", "value").build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).with("name", "value").build(), instanceOf(ArrayTrie.class));

        // index of non visible ASCII characters uses ArrayTernaryTrie
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name\r\n", "value").build(), instanceOf(TreeTrie.class));
        // TODO No case sensitive tree trie!
        // TODO assertThat(new Index.Builder<String>().caseSensitive(true).with("name\r\n", "value").build(), instanceOf(TreeTrie.class));

        // index of non ASCII characters uses ArrayTernaryTrie
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name\u0629!", "value").build(), instanceOf(TreeTrie.class));
        // TODO No case sensitive tree trie!
        // TODO assertThat(new Index.Builder<String>().caseSensitive(true).with("name\u0629!", "value").build(), instanceOf(TreeTrie.class));

        String hugekey = "x".repeat(Character.MAX_VALUE + 1);
        assertTrue(new Index.Builder<String>().caseSensitive(false).with(hugekey, "value").build() instanceof TreeTrie);
        // TODO No case sensitive tree trie!
        // TODO assertTrue(new Index.Builder<String>().caseSensitive(true).with(hugekey, "value").build() instanceof TreeTrie);
    }
    @Test
    public void testMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().build(), instanceOf(ArrayTernaryTrie.Growing.class));
        assertThat(new Index.Builder<String>().mutable().alphabet("visible").build(), instanceOf(ArrayTernaryTrie.Growing.class));
        assertThat(new Index.Builder<String>().mutable().alphabet("invisible\r\n").build(), instanceOf(ArrayTernaryTrie.Growing.class));
        assertThat(new Index.Builder<String>().mutable().alphabet("utf8\u0629").build(), instanceOf(ArrayTernaryTrie.Growing.class));

        assertThat(new Index.Builder<String>().mutable().maxCapacity(256).build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(ArrayTrie.MAX_CAPACITY + 1).build(), instanceOf(TreeTrie.class));
    }
}
