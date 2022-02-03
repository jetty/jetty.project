//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
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
 * from ' ', '+', '-', ':', ';', '.', 'A' to 'Z' or 'a' to 'z'.
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
public class ArrayTrie<V> extends AbstractTrie<V>
{
    /**
     * The Size of a Trie row is how many characters can be looked
     * up directly without going to a big index.  This is set at
     * 32 to cover case insensitive alphabet and a few other common
     * characters.
     */
    private static final int ROW_SIZE = 32;

    /**
     * The maximum capacity of the implementation. Over that,
     * the 16 bit indexes can overflow and the trie
     * cannot find existing entries anymore.
     */
    private static final int MAX_CAPACITY = Character.MAX_VALUE - 1;

    /**
     * The index lookup table, this maps a character as a byte
     * (ISO-8859-1 or UTF8) to an index within a Trie row
     */
    private static final int[] __lookup =
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

    public ArrayTrie()
    {
        this(128);
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
    public ArrayTrie(int capacity)
    {
        super(true);
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("Capacity " + capacity + " > " + MAX_CAPACITY);
        _value = (V[])new Object[capacity + 1];
        _rowIndex = new char[(capacity + 1) * ROW_SIZE];
        _key = new String[capacity + 1];
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
        if (limit > MAX_CAPACITY)
            return false;
        for (k = 0; k < limit; k++)
        {
            char c = s.charAt(k);

            int index = __lookup[c & 0x7f];
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                t = _rowIndex[idx];
                if (t == 0)
                {
                    _rows = (char)MathUtils.cappedAdd(_rows, 1, _value.length);
                    if (_rows == _value.length)
                        return false;
                    t = _rowIndex[idx] = _rows;
                }
            }
            else if (c > 127)
                throw new IllegalArgumentException("non ascii character");
            else
            {
                if (_bigIndex == null)
                    _bigIndex = new char[_value.length][];
                if (t >= _bigIndex.length)
                    return false;
                char[] big = _bigIndex[t];
                if (big == null)
                    big = _bigIndex[t] = new char[128];
                t = big[c];
                if (t == 0)
                {
                    _rows = (char)MathUtils.cappedAdd(_rows, 1, _value.length);
                    if (_rows == _value.length)
                        return false;
                    t = big[c] = _rows;
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
            int index = __lookup[c & 0x7f];
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
                t = big[c & 0x7f];
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
            int index = __lookup[c & 0x7f];
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
                t = big[c];
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
            int index = __lookup[c & 0x7f];
            int nt;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                nt = _rowIndex[idx];
                if (nt == 0)
                    break;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    break;
                nt = big[c];
                if (nt == 0)
                    break;
            }

            // Is this node a match ?
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(nt, s, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                return _value[t];
            }

            t = nt;
        }
        return _value[t];
    }

    private V getBest(int t, byte[] b, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            int index = __lookup[c & 0x7f];
            int nt;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                nt = _rowIndex[idx];
                if (nt == 0)
                    break;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    break;
                nt = big[c];
                if (nt == 0)
                    break;
            }

            // Is this node is a match
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(nt, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
            t = nt;
        }
        return _value[t];
    }

    private V getBest(int t, ByteBuffer b, int offset, int len)
    {
        int pos = b.position() + offset;
        for (int i = 0; i < len; i++)
        {
            if (pos >= b.limit())
                return null;

            byte c = b.get(pos++);
            int index = __lookup[c & 0x7f];
            int nt;
            if (index >= 0)
            {
                int idx = t * ROW_SIZE + index;
                nt = _rowIndex[idx];
                if (nt == 0)
                    break;
            }
            else
            {
                char[] big = _bigIndex == null ? null : _bigIndex[t];
                if (big == null)
                    break;
                nt = big[c];
                if (nt == 0)
                    break;
                t = nt;
            }

            // Is this node a match
            if (_key[t] != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(nt, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                break;
            }
            t = nt;
        }
        return _value[t];
    }

    @Override
    public String toString()
    {

        int rows = _rows;
        if (rows == 0)
            return "{}";

        StringBuilder buf = new StringBuilder();
        char c = '{';

        for (int i = 0; i <= rows; i++)
        {
            String key = _key[i];
            if (key != null)
            {
                buf.append(c).append(key).append('=').append(_value[i]);
                c = ',';
            }
        }
        buf.append('}');
        return buf.toString();
    }

    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();
        for (String k : _key)
            if (k != null)
                keys.add(k);
        return keys;
    }

    @Override
    public boolean isFull()
    {
        return _rows >= _key.length;
    }
}
