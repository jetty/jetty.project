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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>A Ternary Trie String lookup data structure.</p>
 * <p>
 * This Trie is stored in one or more tables and can grow if necessary by adding more tables.
 * Each table contains two arrays:
 * </p>
 * <dl>
 * <dt>char[] _tree</dt><dd>This is semantically 2 dimensional array flattened into a 1 dimensional char array. The second dimension
 * is that every 4 sequential elements represents a row of: character; hi index; eq index; low index, used to build a
 * ternary trie of key strings.</dd>
 * <dt>Node[] _nodes</dt><dd>An array of key and values where each element matches a row in the _tree array. A non zero key element
 * indicates that the _tree row is a complete key rather than an intermediate character of a longer key.
 * A node can also contain links to other tables</dd>
 * </dl>
 * <p>The lookup of a value will iterate through the _tree array matching characters. If the equal tree branch is followed,
 * then the _nodes array is looked up to see if this is a complete match.
 * </p>
 * <p>
 * This Trie may be instantiated either as case sensitive or insensitive.
 * </p>
 * <p>This Trie is not Threadsafe and contains no mutual exclusion
 * or deliberate memory barriers.  It is intended for an ArrayTrie to be
 * built by a single thread and then used concurrently by multiple threads
 * and not mutated during that access.  If concurrent mutations of the
 * Trie is required external locks need to be applied.
 * </p>
 *
 * @param <V> the Entry type
 */
class TernaryTrie<V> extends AbstractTrie<V>
{
    private static final int LO = 1;
    private static final int EQ = 2;
    private static final int HI = 3;

    /**
     * The Size of a Trie row is the char, and the low, equal and high
     * child pointers
     */
    private static final int ROW_SIZE = 4;

    /**
     * The maximum capacity of the implementation. Over that,
     * the 16 bit indexes can overflow and the trie
     * cannot find existing entries anymore.
     */
    private static final int MAX_CAPACITY = Character.MAX_VALUE;

    @SuppressWarnings("unchecked")
    private static class Node<V>
    {
        String _key;
        V _value;
        Table<V>[] _next;

        public Node()
        {
        }

        public Node(String s, V v)
        {
            _key = s;
            _value = v;
        }

        public void set(String s, V v)
        {
            _key = s;
            _value = v;
        }

        @Override
        public String toString()
        {
            return _key + "=" + _value;
        }

        public String dump()
        {
            return String.format("'%s'='%s' [%x,%x,%x]",
                _key,
                _value,
                _next == null ? null : Objects.hashCode(_next[0]),
                _next == null ? null : Objects.hashCode(_next[1]),
                _next == null ? null : Objects.hashCode(_next[2])
                );
        }
    }

    private static class Table<V>
    {
        /**
         * The Trie rows in a single array which allows a lookup of row,character
         * to the next row in the Trie.  This is actually a 2 dimensional
         * array that has been flattened to achieve locality of reference.
         */
        final char[] _tree;
        final Node<V>[] _nodes;
        char _rows;

        @SuppressWarnings("unchecked")
        Table(int capacity)
        {
            _tree = new char[capacity * ROW_SIZE];
            _nodes = new Node[capacity];
        }

        public void clear()
        {
            _rows = 0;
            Arrays.fill(_nodes, null);
            Arrays.fill(_tree, (char)0);
        }
    }

    /**
     * The number of rows allocated
     */
    private final int _maxCapacity;
    private final int _growBy;
    private final Table<V> _root;
    private final List<Table<V>> _tables = new ArrayList<>();
    private Table<V> _tail;
    private int _nodes;

    public static <V> AbstractTrie<V> from(int capacity, int maxCapacity, boolean caseSensitive, Set<Character> alphabet, Map<String, V> contents)
    {
        if (capacity > MAX_CAPACITY)
            capacity = MAX_CAPACITY;

        AbstractTrie<V> trie;
        if (maxCapacity <= 0)
            trie = new TernaryTrie<>(!caseSensitive, capacity, Math.max(capacity, 256), -1);
        else if (capacity < maxCapacity)
            trie = new TernaryTrie<>(!caseSensitive, capacity, Math.min(256, maxCapacity - capacity), maxCapacity);
        else
            trie = new TernaryTrie<>(!caseSensitive, capacity, 0, maxCapacity);

        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    /**
     * Create a Trie
     *
     * @param insensitive true if the Trie is insensitive to the case of the key.
     * @param capacity The capacity of the Trie, which is in the worst case
     * is the total number of characters of all keys stored in the Trie.
     * The capacity needed is dependent of the shared prefixes of the keys.
     * For example, a capacity of 6 nodes is required to store keys "foo"
     * and "bar", but a capacity of only 4 is required to
     * store "bar" and "bat".
     * @param growBy The size to grow the try by, or 0 for non growing Trie
     * @param maxCapacity The maximum capacity the Trie can grow to, or &lt;= 0 for no limit
     *
     */
    @SuppressWarnings("unchecked")
    TernaryTrie(boolean insensitive, int capacity, int growBy, int maxCapacity)
    {
        super(!insensitive);
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("ArrayTernaryTrie maximum capacity overflow (" + capacity + " > " + MAX_CAPACITY + ")");
        _root = new Table<>(capacity + 1);
        _tail = _root;
        _tables.add(_root);
        _growBy = growBy;
        _maxCapacity = _growBy > 0 ? maxCapacity : (capacity + 1);
    }

    @Override
    public void clear()
    {
        _root.clear();
        _tables.clear();
        _tables.add(_root);
        _tail = _root;
        _nodes = 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean put(String s, V v)
    {
        Table<V> table = _root;
        int row = 0;
        int end = s.length();
        for (int i = 0; i < end; i++)
        {
            char c = s.charAt(i);
            if (isCaseInsensitive() && c < 0x80)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                // Do we need to create the new row?
                char nc = table._tree[idx];
                if (nc == 0)
                {
                    nc = table._tree[idx] = c;
                    if (row == table._rows)
                    {
                        table._rows++;
                        _nodes++;
                    }
                }

                Node<V> node = table._nodes[row];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else if (table._rows < table._nodes.length)
                {
                    // point to a new row in this table;
                    row = table._rows;
                    table._tree[idx + branch] = (char)row;
                }
                else if (table != _tail && _tail._rows < _tail._nodes.length)
                {
                    // point to a new row in the tail table;
                    if (node == null)
                        node = table._nodes[row] = new Node<>();
                    if (node._next == null)
                        node._next = new Table[3];
                    row = _tail._rows;
                    table._tree[idx + branch] = (char)row;
                    node._next[branch - 1] = _tail;
                    table = _tail;
                }
                else if (_growBy > 0 && (_maxCapacity <= 0 || _nodes < _maxCapacity))
                {
                    // point to a new row in a new table;
                    if (node == null)
                        node = table._nodes[row] = new Node<>();
                    if (node._next == null)
                        node._next = new Table[3];
                    int growBy = _maxCapacity < 0 ? _growBy : Math.min(_growBy, _maxCapacity - _nodes);
                    table = _tail = new Table<>(growBy);
                    _tables.add(table);
                    row = 0;
                    node._next[branch - 1] = table;
                }
                else
                {
                    return false;
                }

                if (diff == 0)
                    break;
            }
        }

        if (row == table._rows)
        {
            table._rows++;
            _nodes++;
        }

        // Put the key and value
        Node<V> node = table._nodes[row];
        if (node == null)
            table._nodes[row] = new Node<>(s, v);
        else
            node.set(s, v);

        return true;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        Table<V> table = _root;
        int row = 0;
        if (table._rows == 0)
            return null;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0xff)
                return null;
            if (isCaseInsensitive() && c < 0x80)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                Node<V> node = table._nodes[row];
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    return null;
                }

                if (diff == 0)
                    break;
            }
        }

        // Put the key and value
        Node<V> node = table._nodes[row];
        return node != null && node._key != null ? node._value : null;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        Table<V> table = _root;
        int row = 0;
        if (table._rows == 0)
            return null;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(b.position() + offset + i);
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                Node<V> node = table._nodes[row];
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    return null;
                }

                if (diff == 0)
                    break;
            }
        }

        Node<V> node = table._nodes[row];
        return node != null && node._key != null ? node._value : null;
    }

    @Override
    public V getBest(String s)
    {
        return getBest(s, 0, s.length());
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        return getBest(_root, 0, s, offset, len);
    }

    private V getBest(Table<V> table, int row, String s, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0xFF)
                break;
            if (isCaseInsensitive() && c < 0x7f)
                c = StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, s, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (_root._rows == 0)
            return null;
        if (b.hasArray())
            return getBest(_root, 0, b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBest(_root, 0, b, offset, len);
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(_root, 0, b, offset, len);
    }

    private V getBest(Table<V> table, int row, byte[] b, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, b, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    private V getBest(Table<V> table, int row, ByteBuffer b, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            byte c = b.get(b.position() + offset + i);
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, b, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    @Override
    public String toString()
    {
        return "TT@" + Integer.toHexString(hashCode()) + '{' +
            "ci=" + isCaseInsensitive() + ';' +
            _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
                .filter(Objects::nonNull)
                .filter(n -> n._key != null)
                .map(Node::toString)
                .collect(Collectors.joining(",")) +
            '}';
    }

    @Override
    public Set<String> keySet()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .map(n -> n._key)
            .collect(Collectors.toSet());
    }

    public int size()
    {
        return (int)_tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .count();
    }

    public boolean isEmpty()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .anyMatch(n -> n._key != null);
    }

    public Set<Map.Entry<String, V>> entrySet()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .map(n -> new AbstractMap.SimpleEntry<>(n._key, n._value))
            .collect(Collectors.toSet());
    }

    public static int hilo(int diff)
    {
        // branchless equivalent to return ((diff<0)?LO:HI);
        // return 3+2*((diff&Integer.MIN_VALUE)>>Integer.SIZE-1);
        return 1 + (diff | Integer.MAX_VALUE) / (Integer.MAX_VALUE / 2);
    }

    public void dump()
    {
        for (Table<V> table : _tables)
        {
            System.err.println(Integer.toHexString(Objects.hashCode(table)));
            for (int r = 0; r < table._rows; r++)
            {
                char c = table._tree[r * ROW_SIZE];
                System.err.printf("%4d [%s,%d,%d,%d] : %s%n",
                    r,
                    (c < ' ' || c > 127) ? Integer.toHexString(c) : ("'" + c + "'"),
                    (int)table._tree[r * ROW_SIZE + LO],
                    (int)table._tree[r * ROW_SIZE + EQ],
                    (int)table._tree[r * ROW_SIZE + HI],
                    table._nodes[r] == null ? null : table._nodes[r].dump());
            }
        }
    }
}
