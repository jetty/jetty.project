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
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name\r\n", "value").build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).with("name\r\n", "value").build(), instanceOf(TernaryTrie.class));

        String hugekey = "x".repeat(Character.MAX_VALUE + 1);
        assertTrue(new Index.Builder<String>().caseSensitive(false).with(hugekey, "value").build() instanceof TernaryTrie);
        assertTrue(new Index.Builder<String>().caseSensitive(true).with(hugekey, "value").build() instanceof TernaryTrie);
    }

    @Test
    public void testUnlimitdMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().alphabet("visible").build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().alphabet("invisible\r\n").build(), instanceOf(TernaryTrie.class));
    }

    @Test
    public void testLimitedMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).alphabet("visible").build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).alphabet("invisible\r\n").build(), instanceOf(TernaryTrie.class));

        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).alphabet("visible").build(), instanceOf(TernaryTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).alphabet("invisible\r\n").build(), instanceOf(TernaryTrie.class));
    }
}
