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

        // index of ascii characters
        assertThat(new Index.Builder<String>().caseSensitive(false).with("name", "value").build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().caseSensitive(true).with("name", "value").build(), instanceOf(ArrayTrie.class));

        // large index
        String hugekey = "x".repeat(Character.MAX_VALUE + 1);
        assertTrue(new Index.Builder<String>().caseSensitive(false).with(hugekey, "value").build() instanceof TreeTrie);
        assertTrue(new Index.Builder<String>().caseSensitive(true).with(hugekey, "value").build() instanceof TreeTrie);
    }

    @Test
    public void testUnlimitdMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().build(), instanceOf(TreeTrie.class));
    }

    @Test
    public void testLimitedMutableTrieSelection()
    {
        assertThat(new Index.Builder<String>().mutable().maxCapacity(500).build(), instanceOf(ArrayTrie.class));
        assertThat(new Index.Builder<String>().mutable().maxCapacity(Character.MAX_VALUE + 1).build(), instanceOf(TreeTrie.class));
    }
}
