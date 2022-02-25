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
 * <p>This Trie is space efficient if the key characters are
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
    private static final int BIG_ROW_INSENSITIVE = 22;
    private static final int BIG_ROW_SENSITIVE = 48;
    private static final int X = Integer.MIN_VALUE;

    /**
     * The index lookup table, this maps a character as a byte
     * (ISO-8859-1 or UTF8) to a Trie index within a Trie row.
     * Positive values are column indexes within the main {@link #_table}.
     * Negative values are indexes within a {@link Node#_bigRow}.
     * Values of {@link #X} are not indexed and must be searched for
     * in the extended {@link Node#_bigRow}
     */
    private static final int[] LOOKUP_INSENSITIVE =
        {
            //     0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*1*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*2*/ -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, 43, 44, 45, 46, 47,
            /*3*/  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 37, 38, 39, 40, 41, 42,
            /*4*/-12, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*5*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -13, -14, -15, -16, 36,
            /*6*/-17, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*7*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -18, -19, -20, -21,  X,
        };

    /**
     * The index lookup table, this maps a character as a byte
     * (ISO-8859-1 or UTF8) to a Trie index within a Trie row.
     * Positive values are column indexes within the main {@link #_table}.
     * Negative values are indexes within a {@link Node#_bigRow}.
     * Values of {@link #X} are not indexed and must be searched for
     * in the extended {@link Node#_bigRow}
     */
    private static final int[] LOOKUP_SENSITIVE =
        {
            //     0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            /*0*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*1*/  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,  X,
            /*2*/ -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, 43, 44, 45, 46, 47,
            /*3*/  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 37, 38, 39, 40, 41, 42,
            /*4*/-12, -22, -23, -24, -25, -26, -27, -28, -29, -30, -31, -32, -33, -34, -35, -36,
            /*5*/-37, -38, -39, -40, -41, -42, -43, -44, -45, -46, -47, -13, -14, -15, -16, 36,
            /*6*/-17, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            /*7*/ 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -18, -19, -20, -21,  X,
        };

    /**
     * A Node in the tree.
     * A Node instance is only needed for rows in the {@link #_table} array
     * that either have a key/value pair or a {@link #_bigRow} extended row.
     * @param <V> The value type of the node.
     */
    private static class Node<V>
    {
        String _key;
        V _value;

        /**
         * A big row of indexes in which extended characters can be found.
         * The first {@link ArrayTrie#_bigRowSize} entries are accessed by negative
         * indexes from the {@link ArrayTrie#_lookup} table. The following entries
         * are character/row pairs that must be searched looking for a match.
         * A big row is dynamically allocated to minimum size required for it's entries.
         */
        char[] _bigRow;

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
    private final int _bigRowSize;
    private char _rows;

    /** Create a trie from capacity and content
     * @param capacity The maximum capacity of the Trie or -1 for unlimited capacity
     * @param caseSensitive True if the Trie keys are case sensitive
     * @param contents The known contents of the Trie
     * @param <V> The value type of the Trie
     * @return a Trie containing the contents or null if not possible.
     */
    public static <V> ArrayTrie<V> from(int capacity, boolean caseSensitive, Map<String, V> contents)
    {
        // can't do infinite capacity
        if (capacity < 0)
            return null;

        if (capacity > MAX_CAPACITY)
            return null;

        ArrayTrie<V> trie = new ArrayTrie<>(caseSensitive, capacity);
        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    /**
     * @param capacity The capacity of the trie, which at the worst case
     * is the total number of characters of all keys stored in the Trie,
     * plus 1 for the empty key.
     * @see AbstractTrie#requiredCapacity(Set, boolean)
     */
    ArrayTrie(int capacity)
    {
        this(false, capacity);
    }

    @SuppressWarnings("unchecked")
    ArrayTrie(boolean caseSensitive, int capacity)
    {
        super(caseSensitive);
        _bigRowSize = caseSensitive ? BIG_ROW_SENSITIVE : BIG_ROW_INSENSITIVE;
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("Capacity " + capacity + " > " + MAX_CAPACITY);
        _lookup = !caseSensitive ? LOOKUP_INSENSITIVE : LOOKUP_SENSITIVE;
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
            int column = c > 0x7f ? Integer.MIN_VALUE : _lookup[c];
            if (column >= 0)
            {
                // This character is indexed to a column of the main table
                int idx = row * ROW_SIZE + column;
                row = _table[idx];
                if (row == 0)
                {
                    // not found so we need a new row
                    if (_rows == _node.length - 1)
                        return false;
                    row = _table[idx] = ++_rows;
                }
            }
            else if (column != Integer.MIN_VALUE)
            {
                // This character is indexed to a column in the nodes bigRow
                int idx = -column;
                Node<V> node = _node[row];
                if (node == null)
                    node = _node[row] = new Node<>();
                char[] big = node._bigRow;
                row = (big == null || idx >= big.length) ? 0 : big[idx];

                if (row == 0)
                {
                    // Not found, we need a new row
                    if (_rows == _node.length - 1)
                        return false;

                    // Expand the size of the bigRow to have +1 extended lookups
                    if (big == null)
                        big = node._bigRow = new char[idx + 1];
                    else if (idx >= big.length)
                        big = node._bigRow = Arrays.copyOf(big, idx + 1);

                    row = big[idx] = ++_rows;
                }
            }
            else
            {
                // This char is neither in the normal table, nor the first part of a bigRow
                // Look for it linearly in an extended big row.
                int last = row;
                row = 0;
                Node<V> node = _node[last];
                if (node != null)
                {
                    char[] big = node._bigRow;
                    if (big != null)
                    {
                        for (int idx = _bigRowSize; idx < big.length; idx += 2)
                        {
                            if (big[idx] == c)
                            {
                                row = big[idx + 1];
                                break;
                            }
                        }
                    }
                }

                if (row == 0)
                {
                    // Not found, so we need a new row
                    if (_rows == _node.length - 1)
                        return false;

                    if (node == null)
                        node = _node[last] = new Node<>();
                    char[] big = node._bigRow;

                    // Expand the size of the bigRow to have extended lookups
                    if (big == null)
                        big = node._bigRow = new char[_bigRowSize + 2];
                    else
                        big = node._bigRow = Arrays.copyOf(big, Math.max(big.length, _bigRowSize) + 2);

                    // set the lookup char and its row
                    // TODO if the extended big row entries were sorted, then missed lookups could be aborted sooner
                    // TODO and/or a binary chop search could be done for hits.
                    big[big.length - 2] = c;
                    row = big[big.length - 1] = ++_rows;
                }
            }
        }

        // We have processed all characters so set the key and value in the current Node
        Node<V> node = _node[row];
        if (node == null)
            node = _node[row] = new Node<>();
        node._key = key;
        node._value = value;
        return true;
    }

    private int lookup(int row, char c)
    {
        // If the char is small we can lookup in the index table
        if (c < 0x80)
        {
            int column = _lookup[c];
            if (column != Integer.MIN_VALUE)
            {
                // The char is indexed, so should be in normal row or bigRow
                if (column >= 0)
                {
                    // look in the normal row
                    int idx = row * ROW_SIZE + column;
                    row = _table[idx];
                }
                else
                {
                    // Look in the indexed part of the bigRow
                    Node<V> node = _node[row];
                    char[] big = node == null ? null : _node[row]._bigRow;
                    int idx = -column;
                    if (big == null || idx >= big.length)
                        return -1;
                    row = big[idx];
                }
                return row == 0 ? -1 : row;
            }
        }

        // Not an indexed char, so do a linear search through he tail of the bigRow
        Node<V> node = _node[row];
        char[] big = node == null ? null : node._bigRow;
        if (big != null)
        {
            for (int i = _bigRowSize; i < big.length; i += 2)
                if (big[i] == c)
                    return big[i + 1];
        }

        return -1;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        int row = 0;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            row = lookup(row, c);
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
            row = lookup(row, (char)(c & 0xff));
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
            int next = lookup(row, c);
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
            int next = lookup(row, (char)(c & 0xff));
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
            if (pos >= b.limit())
                return null;

            byte c = b.get(pos++);
            int next = lookup(row, (char)(c & 0xff));
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
            "cs=" + isCaseSensitive() + ';' +
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

    public void dumpStdErr()
    {
        System.err.print("row:");
        for (int c = 0; c < ROW_SIZE; c++)
        {
            for (int i = 0; i < 0x7f; i++)
            {
                if (_lookup[i] == c)
                {
                    System.err.printf("  %s", (char)i);
                    break;
                }
            }
        }
        System.err.println();
        System.err.print("big:");
        for (int c = 0; c < _bigRowSize; c++)
        {
            for (int i = 0; i < 0x7f; i++)
            {
                if (-_lookup[i] == c)
                {
                    System.err.printf("  %s", (char)i);
                    break;
                }
            }
        }
        System.err.println();

        for (int row = 0; row <= _rows; row++)
        {
            System.err.printf("%3x:", row);
            for (int c = 0; c < ROW_SIZE; c++)
            {
                char ch = _table[row * ROW_SIZE + c];
                if (ch == 0)
                    System.err.print("  .");
                else
                    System.err.printf("%3x", (int)ch);
            }
            Node<V> node = _node[row];
            if (node != null)
            {
                System.err.printf(" : %s%n", node);
                char[] bigRow = node._bigRow;
                if (bigRow != null)
                {
                    System.err.print("   :");
                    for (int c = 0; c < Math.min(_bigRowSize, bigRow.length); c++)
                    {
                        char ch = bigRow[c];
                        if (ch == 0)
                            System.err.print("  _");
                        else
                            System.err.printf("%3x", (int)ch);
                    }

                    for (int c = _bigRowSize; c < bigRow.length; c += 2)
                        System.err.printf(" %s>%x", bigRow[c], (int)bigRow[c + 1]);

                    System.err.println();
                }
            }
            else
                System.err.println();
        }
        System.err.println();
    }
}
