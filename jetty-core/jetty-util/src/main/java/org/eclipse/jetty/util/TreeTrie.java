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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Trie String lookup data structure using a tree
 * <p>This implementation is always case insensitive and is optimal for
 * a variable number of fixed strings with few special characters.
 * </p>
 * <p>This Trie is stored in a Tree and is unlimited in capacity</p>
 *
 * <p>This Trie is not Threadsafe and contains no mutual exclusion
 * or deliberate memory barriers.  It is intended for an TreeTrie to be
 * built by a single thread and then used concurrently by multiple threads
 * and not mutated during that access.  If concurrent mutations of the
 * Trie is required external locks need to be applied.
 * </p>
 *
 * @param <V> the entry type
 */
class TreeTrie<V> extends AbstractTrie<V>
{
    private static final int[] LOOKUP_INSENSITIVE =
        {
            //    0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*1*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*2*/31, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 26, -1, 27, 30, -1,
            /*3*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 28, 29, -1, -1, -1, -1,
            /*4*/-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            /*5*/15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
            /*6*/-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            /*7*/15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1
        };
    private static final int[] LOOKUP_SENSITIVE =
        {
            //    0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*1*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*2*/31, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 26, -1, 27, 30, -1,
            /*3*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 28, 29, -1, -1, -1, -1,
            /*4*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*5*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*6*/-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            /*7*/15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1
        };
    private static final int INDEX = 32;

    /** Create a trie from capacity and content
     * @param caseSensitive True if the Trie keys are case sensitive
     * @param contents The known contents of the Trie
     * @param <V> The value type of the Trie
     * @return a Trie containing the contents or null if not possible.
     */
    public static <V> AbstractTrie<V> from(boolean caseSensitive, Map<String, V> contents)
    {
        TreeTrie<V> trie = new TreeTrie<>(caseSensitive);
        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    private static class Node<V>
    {
        private final Node<V>[] _nextIndex;
        private final List<Node<V>> _nextOther = new ArrayList<>();
        private final char _c;
        private String _key;
        private V _value;

        // TODO made this use a variable lookup row like ArrayTrie
        @SuppressWarnings("unchecked")
        private Node(char c)
        {
            _nextIndex = new Node[INDEX];
            this._c = c;
        }
    }

    private final int[] _lookup;
    private final Node<V> _root;

    @SuppressWarnings("unchecked")
    TreeTrie()
    {
        this(false);
    }

    TreeTrie(boolean caseSensitive)
    {
        super(caseSensitive);
        _lookup = caseSensitive ? LOOKUP_SENSITIVE : LOOKUP_INSENSITIVE;
        _root = new Node<V>((char)0);
    }

    @Override
    public void clear()
    {
        Arrays.fill(_root._nextIndex, null);
        _root._nextOther.clear();
        _root._key = null;
        _root._value = null;
    }

    @Override
    public boolean put(String s, V v)
    {
        Node<V> t = _root;
        int limit = s.length();
        for (int k = 0; k < limit; k++)
        {
            char c = s.charAt(k);

            int index = c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    t._nextIndex[index] = new Node<V>(c);
                t = t._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int i = t._nextOther.size(); i-- > 0; )
                {
                    n = t._nextOther.get(i);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                {
                    n = new Node<V>(c);
                    t._nextOther.add(n);
                }
                t = n;
            }
        }
        t._key = v == null ? null : s;
        t._value = v;
        return true;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        Node<V> t = _root;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            int index = c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    return null;
                t = t._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int j = t._nextOther.size(); j-- > 0; )
                {
                    n = t._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    return null;
                t = n;
            }
        }
        return t._value;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        Node<V> t = _root;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(offset + i);
            int index = c >= 0 && c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    return null;
                t = t._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int j = t._nextOther.size(); j-- > 0; )
                {
                    n = t._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    return null;
                t = n;
            }
        }
        return t._value;
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(_root, b, offset, len);
    }

    private V getBest(Node<V> node, byte[] b, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            Node<V> next;
            byte c = b[offset + i];
            int index = c >= 0 && c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (node._nextIndex[index] == null)
                    break;
                next = node._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int j = node._nextOther.size(); j-- > 0; )
                {
                    n = node._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                next = n;
            }

            // Is the next Trie is a match
            if (node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
            node = next;
        }
        return node._value;
    }

    @Override
    public boolean isEmpty()
    {
        return !hasAnyKey(_root);
    }

    private boolean hasAnyKey(Node<V> t)
    {
        if (t != null)
        {
            if (t._key != null)
                return true;

            for (int i = 0; i < INDEX; i++)
            {
                if (t._nextIndex[i] != null)
                {
                    if (hasAnyKey(t._nextIndex[i]))
                        return true;
                }
            }
            for (int i = t._nextOther.size(); i-- > 0; )
            {
                if (hasAnyKey(t._nextOther.get(i)))
                    return true;
            }
        }
        return false;
    }

    @Override
    public int size()
    {
        return keySet().size();
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        return getBest(_root, s, offset, len);
    }

    private V getBest(Node<V> node, String s, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            Node<V> next;
            char c = s.charAt(offset + i);
            int index = c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (node._nextIndex[index] == null)
                    break;
                next = node._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int j = node._nextOther.size(); j-- > 0; )
                {
                    n = node._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                next = n;
            }

            // Is the next Trie is a match
            if (node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, s, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }

            node = next;
        }
        return node._value;
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (b.hasArray())
            return getBest(b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBest(_root, b, offset, len);
    }

    private V getBest(Node<V> node, ByteBuffer b, int offset, int len)
    {
        Node<V> next;
        int pos = b.position() + offset;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(pos++);
            int index = c >= 0 && c < 0x7f ? _lookup[c] : -1;
            if (index >= 0)
            {
                if (node._nextIndex[index] == null)
                    break;
                next = node._nextIndex[index];
            }
            else
            {
                Node<V> n = null;
                for (int j = node._nextOther.size(); j-- > 0; )
                {
                    n = node._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                next = n;
            }

            // Is the next Trie is a match
            if (node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
            node = next;
        }
        return node._value;
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append("TT@").append(Integer.toHexString(hashCode())).append('{');
        buf.append("ci=").append(isCaseInsensitive()).append(';');
        toString(buf, _root, "");
        buf.append('}');
        return buf.toString();
    }

    private static <V> void toString(Appendable out, Node<V> t, String separator)
    {
        loop: while (true)
        {
            if (t != null)
            {
                if (t._value != null)
                {
                    try
                    {
                        out.append(separator);
                        separator = ",";
                        out.append(t._key);
                        out.append('=');
                        out.append(t._value.toString());
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                }

                for (int i = 0; i < INDEX;)
                {
                    Node<V> n = t._nextIndex[i++];
                    if (n != null)
                    {
                        // can we avoid tail recurse?
                        if (i == INDEX && t._nextOther.size() == 0)
                        {
                            t = n;
                            continue loop;
                        }
                        // recurse
                        toString(out, n, separator);
                    }
                }
                for (int i = t._nextOther.size(); i-- > 0; )
                {
                    // can we avoid tail recurse?
                    if (i == 0)
                    {
                        t = t._nextOther.get(i);
                        continue loop;
                    }
                    toString(out, t._nextOther.get(i), separator);
                }
            }

            break;
        }
    }

    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();
        keySet(keys, _root);
        return keys;
    }

    private static <V> void keySet(Set<String> set, Node<V> t)
    {
        if (t != null)
        {
            if (t._key != null)
                set.add(t._key);

            for (int i = 0; i < INDEX; i++)
            {
                if (t._nextIndex[i] != null)
                    keySet(set, t._nextIndex[i]);
            }
            for (int i = t._nextOther.size(); i-- > 0; )
            {
                keySet(set, t._nextOther.get(i));
            }
        }
    }
}
