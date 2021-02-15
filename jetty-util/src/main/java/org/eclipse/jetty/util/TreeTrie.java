//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
public class TreeTrie<V> extends AbstractTrie<V>
{
    private static final int[] LOOKUP =
        {
            // 0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*1*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            /*2*/31, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 26, -1, 27, 30, -1,
            /*3*/-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 28, 29, -1, -1, -1, -1,
            /*4*/-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            /*5*/15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
            /*6*/-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            /*7*/15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1
        };
    private static final int INDEX = 32;
    private final TreeTrie<V>[] _nextIndex;
    private final List<TreeTrie<V>> _nextOther = new ArrayList<>();
    private final char _c;
    private String _key;
    private V _value;

    public TreeTrie()
    {
        super(true);
        _nextIndex = new TreeTrie[INDEX];
        _c = 0;
    }

    private TreeTrie(char c)
    {
        super(true);
        _nextIndex = new TreeTrie[INDEX];
        this._c = c;
    }

    @Override
    public void clear()
    {
        Arrays.fill(_nextIndex, null);
        _nextOther.clear();
        _key = null;
        _value = null;
    }

    @Override
    public boolean put(String s, V v)
    {
        TreeTrie<V> t = this;
        int limit = s.length();
        for (int k = 0; k < limit; k++)
        {
            char c = s.charAt(k);

            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    t._nextIndex[index] = new TreeTrie<V>(c);
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
                for (int i = t._nextOther.size(); i-- > 0; )
                {
                    n = t._nextOther.get(i);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                {
                    n = new TreeTrie<V>(c);
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
        TreeTrie<V> t = this;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    return null;
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
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
        TreeTrie<V> t = this;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(offset + i);
            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    return null;
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
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
        TreeTrie<V> t = this;
        for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    break;
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
                for (int j = t._nextOther.size(); j-- > 0; )
                {
                    n = t._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                t = n;
            }

            // Is the next Trie is a match
            if (t._key != null)
            {
                // Recurse so we can remember this possibility
                V best = t.getBest(b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
        }
        return t._value;
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        TreeTrie<V> t = this;
        for (int i = 0; i < len; i++)
        {
            byte c = (byte)(0xff & s.charAt(offset + i));
            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    break;
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
                for (int j = t._nextOther.size(); j-- > 0; )
                {
                    n = t._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                t = n;
            }

            // Is the next Trie is a match
            if (t._key != null)
            {
                // Recurse so we can remember this possibility
                V best = t.getBest(s, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
        }
        return t._value;
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (b.hasArray())
            return getBest(b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBestByteBuffer(b, offset, len);
    }

    private V getBestByteBuffer(ByteBuffer b, int offset, int len)
    {
        TreeTrie<V> t = this;
        int pos = b.position() + offset;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(pos++);
            int index = c >= 0 && c < 0x7f ? LOOKUP[c] : -1;
            if (index >= 0)
            {
                if (t._nextIndex[index] == null)
                    break;
                t = t._nextIndex[index];
            }
            else
            {
                TreeTrie<V> n = null;
                for (int j = t._nextOther.size(); j-- > 0; )
                {
                    n = t._nextOther.get(j);
                    if (n._c == c)
                        break;
                    n = null;
                }
                if (n == null)
                    break;
                t = n;
            }

            // Is the next Trie is a match
            if (t._key != null)
            {
                // Recurse so we can remember this possibility
                V best = t.getBest(b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
        }
        return t._value;
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        toString(buf, this);

        if (buf.length() == 0)
            return "{}";

        buf.setCharAt(0, '{');
        buf.append('}');
        return buf.toString();
    }

    private static <V> void toString(Appendable out, TreeTrie<V> t)
    {
        if (t != null)
        {
            if (t._value != null)
            {
                try
                {
                    out.append(',');
                    out.append(t._key);
                    out.append('=');
                    out.append(t._value.toString());
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

            for (int i = 0; i < INDEX; i++)
            {
                if (t._nextIndex[i] != null)
                    toString(out, t._nextIndex[i]);
            }
            for (int i = t._nextOther.size(); i-- > 0; )
            {
                toString(out, t._nextOther.get(i));
            }
        }
    }

    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();
        keySet(keys, this);
        return keys;
    }

    private static <V> void keySet(Set<String> set, TreeTrie<V> t)
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

    @Override
    public boolean isFull()
    {
        return false;
    }
}
