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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p>A Trie String lookup data structure using a fixed size array.</p>
 * <p>This implementation is always case insensitive and is optimal for
 * a small number of fixed strings with few special characters.  The
 * Trie is stored in an array of lookup tables, each indexed by the
 * next character of the key.   Frequently used characters are directly
 * indexed in each lookup table, whilst infrequently used characters
 * must use a big character table.
 * </p>
 * <p>This Trie is very space efficient if the key characters are
 * from ' ', '+', '-', ':', ';', '.', '0' - '9', A' to 'Z' or 'a' to 'z'
 * Other ISO-8859-1 characters can be used by the key, but less space
 * efficiently.
 * </p>
 * <p>This Trie is not Threadsafe and contains no mutual exclusion
 * or deliberate memory barriers.  It is intended for an ArrayTrie to be
 * built by a single thread and then used concurrently by multiple threads
 * and not mutated during that access.  If concurrent mutations of the
 * Trie is required external locks need to be applied.
 * </p>
 *
 * @param <V> the entry type
 */
class ArrayTrie<V> extends AbstractTrie<V>
{
    /**
     * The Size of a Trie row is how many characters can be looked
     * up directly without going to a big index.  This is set at
     * 32 to cover case insensitive alphabet and a few other common
     * characters.
     */
    private static final int ROW_SIZE = 48;
    private static final int BIG_ROW = 22;

    /**
     * The index lookup table, this maps a character as a byte
     * (ISO-8859-1 or UTF8) to an index within a Trie row
     */
    // TODO if we expanded ROW_SIZE to 48 we could include 0-9, _ and a few other chars
    // TODO Also We could have a case sensitive table with a larger row size, 58 without digits, 69 with digits and _
    // TODO Also see idea bleow in put for making big index more space efficient
    private static final int X = Integer.MIN_VALUE;
    private static final int[] LOOKUP_INSENSITIVE =
        {
            //     0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*1*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*2*/ -1, -2, -3, -4, -5, -6, -7, -8, -9,-10,-11, 43, 44, 45, 46, 47,
            /*3*/  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 37, 38, 39, 40, 41, 42,
            /*4*/-12, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*5*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,-13,-14,-15,-16, 36,
            /*6*/-17, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*7*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,-18,-19,-20,-21,  X,
        };

    private static final int[] LOOKUP_SENSITIVE =
        {
            //     0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*1*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*2*/ -1, -2, -3, -4, -5, -6, -7, -8, -9,-10,-11, 43, 44, 45, 46, 47,
            /*3*/  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 37, 38, 39, 40, 41, 42,
            /*4*/-12,-22,-23,-24,-25,-26,-27,-28,-29,-30,-31,-32,-33,-34,-35,-36,
            /*5*/-37,-38,-39,-40,-41,-42,-43,-44,-45,-46,-47,-13,-14,-15,-16, 36,
            /*6*/-17, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*7*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,-18,-19,-20,-21,  X,
        };

    private final int[] _lookup;

    /**
     * The Trie rows in a single array which allows a lookup of row,character
     * to the next row in the Trie.  This is actually a 2 dimensional
     * array that has been flattened to achieve locality of reference.
     * The first ROW_SIZE entries are for row 0, then next ROW_SIZE
     * entries are for row 1 etc.   So in general instead of using
     * _rows[row][index], we use _rows[row*ROW_SIZE+index] to look up
     * the next row for a given character.
     *
     * The array is of characters rather than integers to save space.
     */
    private final char[] _rowIndex;

    /**
     * The key (if any) for a Trie row.
     * A row may be a leaf, a node or both in the Trie tree.
     */
    private final String[] _key;

    /**
     * The value (if any) for a Trie row.
     * A row may be a leaf, a node or both in the Trie tree.
     */
    private final V[] _value;

    /**
     * A big index for each row.
     * If a character outside of the lookup map is needed,
     * then a big index will be created for the row, with
     * 256 entries, one for each possible byte.
     */
    private char[][] _bigIndex;

    /**
     * The number of rows allocated
     */
    private char _rows;

    public static <V> ArrayTrie<V> from(int capacity, int maxCapacity, boolean caseSensitive, Set<Character> alphabet, Map<String, V> contents)
    {
        // can't do case sensitive or infinite capacity
        if (maxCapacity < 0)
            return null;

        // TODO can we increase capacity by dividing by ROW_SIZE?
        if ((maxCapacity * ROW_SIZE) > Character.MAX_VALUE)
            return null;

        // check alphabet
        int[] lookup = caseSensitive ? LOOKUP_SENSITIVE : LOOKUP_INSENSITIVE;
        for (Character c : alphabet)
        {
            if (c > 0x7F  || lookup[c & 0x7f] == Integer.MIN_VALUE)
                return null;
        }

        // TODO it would be possible to do a frequency analysis on the contents and come up with a custom LOOKUP table!
        ArrayTrie<V> trie = new ArrayTrie<>(!caseSensitive, maxCapacity);
        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    /**
     * @param capacity The capacity of the trie, which at the worst case
     * is the total number of characters of all keys stored in the Trie.
     * The capacity needed is dependent of the shared prefixes of the keys.
     * For example, a capacity of 6 nodes is required to store keys "foo"
     * and "bar", but a capacity of only 4 is required to
     * store "bar" and "bat".
     */
    @SuppressWarnings("unchecked")
    ArrayTrie(int capacity)
    {
        this(true, capacity);
    }

    ArrayTrie(boolean caseInsensitive, int capacity)
    {
        super(caseInsensitive);
        _lookup = caseInsensitive ? LOOKUP_INSENSITIVE : LOOKUP_SENSITIVE;
        capacity++;
        _value = (V[])new Object[capacity];
        _rowIndex = new char[capacity * ROW_SIZE];
        _key = new String[capacity];
    }

    @Override
    public void clear()
    {
        _rows = 0;
        Arrays.fill(_value, null);
        Arrays.fill(_rowIndex, (char)0);
        Arrays.fill(_key, null);
    }

    @Override
    public boolean put(String s, V v)
    {
        int t = 0;
        int k;
        int limit = s.length();
        for (k = 0; k < limit; k++)
        {
            char c = s.charAt(k);
            if (c > 0x7f)
                throw new IllegalArgumentException("non ascii character");

            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                throw new IllegalArgumentException("unsupported character");
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                if (idx >= _rowIndex.length)
                    return false;
                t = _rowIndex[idx];
                if (t == 0)
                {
                    if (++_rows >= _value.length)
                        return false;
                    t = _rowIndex[idx] = _rows;
                }
            }
            else
            {
                if (_bigIndex == null)
                    _bigIndex = new char[_value.length][];
                if (t >= _bigIndex.length)
                    return false;
                char[] big = _bigIndex[t];
                if (big == null)
                    big = _bigIndex[t] = new char[_lookup == LOOKUP_INSENSITIVE ? BIG_ROW : ROW_SIZE];
                t = big[-index];
                if (t == 0)
                {
                    if (_rows == _value.length)
                        return false;
                    t = big[-index] = ++_rows;
                }
            }
        }

        if (t >= _key.length)
        {
            _rows = (char)_key.length;
            return false;
        }

        _key[t] = v == null ? null : s;
        _value[t] = v;
        return true;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        int t = 0;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0x7f)
                return null;
            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                return null;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                t = _rowIndex[idx];
                if (t == 0)
                    return null;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    return null;
                t = big[-index];
                if (t == 0)
                    return null;
            }
        }
        return _value[t];
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        int t = 0;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(offset + i);
            if (c < 0)
                return null;
            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                return null;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                t = _rowIndex[idx];
                if (t == 0)
                    return null;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    return null;
                t = big[-index];
                if (t == 0)
                    return null;
            }
        }
        return _value[t];
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(0, b, offset, len);
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (b.hasArray())
            return getBest(0, b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBest(0, b, offset, len);
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        return getBest(0, s, offset, len);
    }

    private V getBest(int t, String s, int offset, int len)
    {
        int pos = offset;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(pos++);
            if (c > 0x7f)
                break;
            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                break;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                int nt = _rowIndex[idx];
                if (nt == 0)
                    break;
                t = nt;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    return null;
                int nt = big[-index];
                if (nt == 0)
                    break;
                t = nt;
            }

            // Is the next Trie is a match
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(t, s, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                return _value[t];
            }
        }
        return _value[t];
    }

    private V getBest(int t, byte[] b, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            if (c < 0)
                break;
            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                break;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                int nt = _rowIndex[idx];
                if (nt == 0)
                    break;
                t = nt;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    return null;
                int nt = big[-index];
                if (nt == 0)
                    break;
                t = nt;
            }

            // Is the next Trie is a match
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(t, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
        }
        return _value[t];
    }

    private V getBest(int t, ByteBuffer b, int offset, int len)
    {
        int pos = b.position() + offset;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(pos++);
            if (c < 0)
                break;
            int index = _lookup[c & 0x7f];
            if (index == Integer.MIN_VALUE)
                break;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                int nt = _rowIndex[idx];
                if (nt == 0)
                    break;
                t = nt;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    return null;
                int nt = big[-index];
                if (nt == 0)
                    break;
                t = nt;
            }

            // Is the next Trie is a match
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(t, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
        }
        return _value[t];
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append("AT@").append(Integer.toHexString(hashCode())).append('{');
        buf.append("ci=").append(isCaseInsensitive()).append(';');
        buf.append("c=").append(_rowIndex.length / ROW_SIZE).append(';');
        toString(buf, 0);
        buf.append('}');
        return buf.toString();
    }

    private void toString(Appendable out, int t)
    {
        if (_value[t] != null)
        {
            try
            {
                if (t != 0)
                    out.append(',');
                out.append(_key[t]);
                out.append('=');
                out.append(_value[t].toString());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < ROW_SIZE; i++)
        {
            int idx = t * ROW_SIZE + i;
            if (_rowIndex[idx] != 0)
                toString(out, _rowIndex[idx]);
        }

        char[] big = _bigIndex == null ? null : _bigIndex[t];
        if (big != null)
        {
            for (int i : big)
            {
                if (i != 0)
                    toString(out, i);
            }
        }
    }

    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();
        keySet(keys, 0);
        return keys;
    }

    @Override
    public int size()
    {
        return keySet().size();
    }

    @Override
    public boolean isEmpty()
    {
        return keySet().isEmpty();
    }

    private void keySet(Set<String> set, int t)
    {
        if (t < _value.length && _value[t] != null)
            set.add(_key[t]);

        for (int i = 0; i < ROW_SIZE; i++)
        {
            int idx = t * ROW_SIZE + i;
            if (idx < _rowIndex.length && _rowIndex[idx] != 0)
                keySet(set, _rowIndex[idx]);
        }

        char[] big = _bigIndex == null || t >= _bigIndex.length ? null : _bigIndex[t];
        if (big != null)
        {
            for (int i : big)
            {
                if (i != 0)
                    keySet(set, i);
            }
        }
    }
}
