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

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;

/**
 * An empty trie implementation that never contains anything and never accepts new entries.
 * @param <V> the entry type
 */
class EmptyTrie<V> extends AbstractTrie<V>
{
    @SuppressWarnings("rawtypes")
    private static final EmptyTrie SENSITIVE = new EmptyTrie<>(false);
    @SuppressWarnings("rawtypes")
    private static final EmptyTrie INSENSITIVE = new EmptyTrie<>(true);

    @SuppressWarnings("unchecked")
    public static <V> EmptyTrie<V> instance(boolean caseSensitive)
    {
        return caseSensitive ? SENSITIVE : INSENSITIVE;
    }

    private EmptyTrie(boolean insensitive)
    {
        super(insensitive);
    }

    @Override
    public boolean put(String s, V v)
    {
        return false;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        return null;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        return null;
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        return null;
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        return null;
    }

    @Override
    public boolean isEmpty()
    {
        return true;
    }

    @Override
    public Set<String> keySet()
    {
        return Collections.emptySet();
    }

    @Override
    public int size()
    {
        return 0;
    }

    @Override
    public void clear()
    {
    }
}
