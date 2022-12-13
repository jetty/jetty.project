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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Abstract Trie implementation.
 * <p>Provides some common implementations, which may not be the most
 * efficient. For byte operations, the assumption is made that the charset
 * is ISO-8859-1</p>
 *
 * @param <V> the type of object that the Trie holds
 */
abstract class AbstractTrie<V> implements Index.Mutable<V>
{
    final boolean _caseSensitive;

    protected AbstractTrie(boolean caseSensitive)
    {
        _caseSensitive = caseSensitive;
    }

    public boolean isCaseInsensitive()
    {
        return !_caseSensitive;
    }

    public boolean isCaseSensitive()
    {
        return _caseSensitive;
    }

    public boolean put(V v)
    {
        return put(v.toString(), v);
    }

    public V remove(String s)
    {
        V o = get(s);
        put(s, null);
        return o;
    }

    public V get(String s)
    {
        return get(s, 0, s.length());
    }

    public V get(ByteBuffer b)
    {
        return get(b, 0, b.remaining());
    }

    public V getBest(String s)
    {
        return getBest(s, 0, s.length());
    }

    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(new String(b, offset, len, StandardCharsets.ISO_8859_1));
    }

    /**
     * Calculate required Trie capacity in nodes of a tree decomposition of the keys.
     * For example given the keys:
     * <ul>
     *     <li>utf_16</li>
     *     <li>utf_8</li>
     *     <li>utf16</li>
     *     <li>utf8</li>
     * </ul>
     * The tree switching by character is:
     * <pre>
     *                            1 - 6
     *                          /
     *                        _ - 8
     *                      /
     *     root - u - t - f - 1 - 6
     *                      \
     *                        8
     * </pre>
     * The count also applies to ternary trees as follows:
     * <pre>
     *     root - u - t - f - _ ----- 1 - 6
     *                         \       \
     *                          1 - 6   8
     *                           \
     *                            8
     * </pre>
     * In both cases above there are 10 character nodes plus the root node that can
     * hold a value for the empty string key, so the returned capacity is 11.
     *
     * @param keys The keys to be put in a Trie
     * @param caseSensitive true if the capacity should be calculated with case-sensitive keys
     * @return The capacity in nodes of a tree decomposition
     */
    protected static int requiredCapacity(Set<String> keys, boolean caseSensitive)
    {
        List<String> list = caseSensitive
            ? new ArrayList<>(keys)
            : keys.stream().map(String::toLowerCase).collect(Collectors.toList());
        Collections.sort(list);
        return 1 + AbstractTrie.requiredCapacity(list, 0, list.size(), 0);
    }

    /**
     * Calculate required Trie capacity in nodes of a sub-tree decomposition of the keys.
     * @param keys The keys to calculate the capacity for
     * @param offset The offset of the first key to be considered
     * @param length The number of keys to be considered
     * @param index The character to be considered
     * @return The capacity in tree nodes of the substree
     */
    private static int requiredCapacity(List<String> keys, int offset, int length, int index)
    {
        int required = 0;

        while (true)
        {
            // Examine all the keys in the subtree
            Character nodeChar = null;
            for (int i = 0; i < length; i++)
            {
                String k = keys.get(offset + i);

                // If the key is shorter than our current index then ignore it
                if (k.length() <= index)
                    continue;

                // Get the character at the index of the current key
                char c = k.charAt(index);

                // If the character is the same as the current node, then we are
                // still in the current node and need to continue searching for the
                // next node or the end of the keys
                if (nodeChar != null && c == nodeChar)
                    continue;

                // The character is a new node, so increase required by 1
                required++;

                // if we had a previous node, then add the required nodes for the subtree under it.
                if (nodeChar != null)
                    required += AbstractTrie.requiredCapacity(keys, offset, i, index + 1);

                // set the char for the new node
                nodeChar = c;

                // reset the offset, length and index to continue iteration from the start of the new node
                offset += i;
                length -= i;
                i = 0;
            }

            // If we finish the iteration with a nodeChar, then we must add the required nodes for the subtree under it.
            if (nodeChar != null)
            {
                // instead of recursion here, we loop to avoid tail recursion
                index++;
                continue;
            }

            return required;
        }
    }

    protected boolean putAll(Map<String, V> contents)
    {
        for (Map.Entry<String, V> entry : contents.entrySet())
        {
            if (!put(entry.getKey(), entry.getValue()))
                return false;
        }
        return true;
    }
}
