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
import java.nio.charset.StandardCharsets;

/**
 * Abstract Trie implementation.
 * <p>Provides some common implementations, which may not be the most
 * efficient. For byte operations, the assumption is made that the charset
 * is ISO-8859-1</p>
 *
 * @param <V> the type of object that the Trie holds
 */
public abstract class AbstractTrie<V> implements Trie<V>
{
    final boolean _caseInsensitive;

    protected AbstractTrie(boolean insensitive)
    {
        _caseInsensitive = insensitive;
    }

    @Override
    public boolean put(V v)
    {
        return put(v.toString(), v);
    }

    @Override
    public V remove(String s)
    {
        V o = get(s);
        put(s, null);
        return o;
    }

    @Override
    public V get(String s)
    {
        return get(s, 0, s.length());
    }

    @Override
    public V get(ByteBuffer b)
    {
        return get(b, 0, b.remaining());
    }

    @Override
    public V getBest(String s)
    {
        return getBest(s, 0, s.length());
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(new String(b, offset, len, StandardCharsets.ISO_8859_1));
    }

    @Override
    public boolean isCaseInsensitive()
    {
        return _caseInsensitive;
    }
}
