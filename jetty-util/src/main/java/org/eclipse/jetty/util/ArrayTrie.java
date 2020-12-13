//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    public static int MAX_CAPACITY = Character.MAX_VALUE;
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

    private static class Node<V>
    {
        String _key;
        V _value;
        char[] _bigIndex;

        @Override
        public String toString()
        {
            return _key + "=" + _value;
        }
    }

    /**
     * The Trie rows in a single array which allows a lookup of row,character
     * to the next row in the Trie.  This is actually a 2 dimensional
     * array that has been flattened to achieve locality of reference.
     * The first ROW_SIZE entries are for row 0, then next ROW_SIZE
     * entries are for row 1 etc.   So in general instead of using
     * _rows[row][column], we use _rows[row*ROW_SIZE+column] to look up
     * the next row for a given character.
     *
     * The array is of characters rather than integers to save space.
     */
    private final char[] _table;
    private final int[] _lookup;
    private final Node<V>[] _node;
    private char _rows;

    public static <V> ArrayTrie<V> from(int capacity, int maxCapacity, boolean caseSensitive, Set<Character> alphabet, Map<String, V> contents)
    {
        // can't do infinite capacity
        if (maxCapacity < 0)
            return null;

        if (maxCapacity > MAX_CAPACITY || capacity > MAX_CAPACITY)
            return null;

        // check alphabet
        if (alphabet == null)
            return null;
        if (alphabet != Index.Mutable.Builder.VISIBLE_ASCII_ALPHABET)
        {
            int[] lookup = caseSensitive ? LOOKUP_SENSITIVE : LOOKUP_INSENSITIVE;
            for (Character c : alphabet)
            {
                if (c > 0x7F || lookup[c & 0x7f] == Integer.MIN_VALUE)
                    return null;
            }
        }

        ArrayTrie<V> trie = new ArrayTrie<>(caseSensitive, maxCapacity);
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
    ArrayTrie(int capacity)
    {
        this(false, capacity);
    }

    @SuppressWarnings("unchecked")
    ArrayTrie(boolean caseSensitive, int capacity)
    {
        super(caseSensitive);
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("Capacity " + capacity + " > " + MAX_CAPACITY);
        _lookup = !caseSensitive ? LOOKUP_INSENSITIVE : LOOKUP_SENSITIVE;
        capacity++;
        _table = new char[capacity * ROW_SIZE];
        _node = new Node[capacity];
    }

    @Override
    public void clear()
    {
        _rows = 0;
        Arrays.fill(_table, (char)0);
        Arrays.fill(_node, null);
    }

    @Override
    public boolean put(String key, V value)
    {
        int row = 0;
        int limit = key.length();
        for (int i = 0; i < limit; i++)
        {
            char c = key.charAt(i);
            if (c > 0x7f)
                throw new IllegalArgumentException("non ascii character");

            int column = _lookup[c & 0x7f];
            if (column == Integer.MIN_VALUE)
                throw new IllegalArgumentException("unsupported character");
            if (column >= 0)
            {
                int idx = row * ROW_SIZE + column;
                if (idx >= _table.length)
                    return false;
                row = _table[idx];
                if (row == 0)
                {
                    if (++_rows >= _node.length)
                        return false;
                    row = _table[idx] = _rows;
                }
            }
            else
            {
                if (row >= _node.length)
                    return false;
                Node<V> node = _node[row];
                if (node == null)
                    node = _node[row] = new Node<>();
                char[] big = node._bigIndex;
                if (big == null)
                    big = node._bigIndex = new char[_lookup == LOOKUP_INSENSITIVE ? BIG_ROW : ROW_SIZE];
                row = big[-column];
                if (row == 0)
                {
                    if (_rows == _node.length)
                        return false;
                    row = big[-column] = ++_rows;
                }
            }
        }

        if (row >= _node.length)
        {
            _rows = (char)_node.length;
            return false;
        }

        Node<V> node = _node[row];
        if (node == null)
            node = _node[row] = new Node<>();
        node._key = key;
        node._value = value;
        return true;
    }

    private int lookup(int row, int c)
    {
        int column = _lookup[c];
        if (column == Integer.MIN_VALUE)
            return -1;
        if (column >= 0)
        {
            int idx = row * ROW_SIZE + column;
            row = _table[idx];
        }
        else
        {
            Node<V> node = _node[row];
            char[] big = node == null ? null : _node[row]._bigIndex;
            if (big == null)
                return -1;
            row = big[-column];
        }
        return row == 0 ? -1 : row;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        int row = 0;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0x7f)
                return null;
            row = lookup(row, c & 0x7f);
            if (row < 0)
                return null;
        }
        Node<V> node = _node[row];
        return node == null ? null : node._value;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        int row = 0;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(offset + i);
            if (c < 0)
                return null;
            row = lookup(row, c & 0x7f);
            if (row < 0)
                return null;
        }
        Node<V> node = _node[row];
        return node == null ? null : node._value;
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

    private V getBest(int row, String s, int offset, int len)
    {
        int pos = offset;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(pos++);
            if (c > 0x7f)
                break;

            int next = lookup(row, c & 0x7f);
            if (next < 0)
                break;

            // Is the row a match?
            Node<V> node = _node[row];
            if (node != null && node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, s, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                return node._value;
            }

            row = next;
        }
        Node<V> node = _node[row];
        return node == null ? null : node._value;
    }

    private V getBest(int row, byte[] b, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            if (c < 0)
                break;
            int next = lookup(row, c & 0x7f);
            if (next < 0)
                break;

            // Is the next row a match?
            Node<V> node = _node[row];
            if (node != null && node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                return node._value;
            }

            row = next;
        }
        Node<V> node = _node[row];
        return node == null ? null : node._value;
    }

    private V getBest(int row, ByteBuffer b, int offset, int len)
    {
        int pos = b.position() + offset;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(pos++);
            if (c < 0)
                break;
            int next = lookup(row, c & 0x7f);
            if (next < 0)
                break;

            // Is the next row a match?
            Node<V> node = _node[row];
            if (node != null && node._key != null)
            {
                // Recurse so we can remember this possibility
                V best = getBest(next, b, offset + i + 1, len - i - 1);
                if (best != null)
                    return best;
                return node._value;
            }

            row = next;
        }
        Node<V> node = _node[row];
        return node == null ? null : node._value;
    }

    @Override
    public String toString()
    {
        return
            "AT@" + Integer.toHexString(hashCode()) + '{' +
            "ci=" + isCaseInsensitive() + ';' +
            "c=" + _table.length / ROW_SIZE + ';' +
            Arrays.stream(_node)
                .filter(n -> n != null && n._key != null)
                .map(Node::toString)
                .collect(Collectors.joining(",")) +
            '}';
    }

    @Override
    public Set<String> keySet()
    {
        return Arrays.stream(_node)
            .filter(Objects::nonNull)
            .map(n -> n._key)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
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
}
