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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An immutable String lookup data structure.
 * @param <V> the entry type
 */
public interface Index<V>
{
    /**
     * Get an exact match from a String key
     *
     * @param s The key
     * @return the value for the string key
     */
    V get(String s);

    /**
     * Get an exact match from a segment of a ByteBuufer as key
     *
     * @param b The buffer
     * @return The value or null if not found
     */
    V get(ByteBuffer b);

    /**
     * Get an exact match from a String key
     *
     * @param s The key
     * @param offset The offset within the string of the key
     * @param len the length of the key
     * @return the value for the string / offset / length
     */
    V get(String s, int offset, int len);

    /**
     * Get an exact match from a segment of a ByteBuufer as key
     *
     * @param b The buffer
     * @param offset The offset within the buffer of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V get(ByteBuffer b, int offset, int len);

    /**
     * Get the best match from key in a String.
     *
     * @param s The string
     * @param offset The offset within the string of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(String s, int offset, int len);

    /**
     * Get the best match from key in a byte buffer.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @param offset The offset within the buffer of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(ByteBuffer b, int offset, int len);

    /**
     * Get the best match from key in a String.
     *
     * @param s The string
     * @return The value or null if not found
     */
    V getBest(String s);

    /**
     * Get the best match from key in a byte array.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @param offset The offset within the array of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(byte[] b, int offset, int len);

    /**
     * Check if the index contains any entry.
     *
     * @return true if the index does not contain any entry.
     */
    boolean isEmpty();

    /**
     * Get the number of entries in the index.
     *
     * @return the index' entries count.
     */
    int size();

    /**
     * Get a {@link Set} of the keys contained in this index.
     *
     * @return a {@link Set} of the keys contained in this index.
     */
    Set<String> keySet();

    /**
     * A mutable String lookup data structure.
     * Implementations are not thread-safe.
     * @param <V> the entry type
     */
    interface Mutable<V> extends Index<V>
    {
        /**
         * Put an entry into the index.
         *
         * @param s The key for the entry
         * @param v The value of the entry
         * @return True if the index had capacity to add the field.
         */
        boolean put(String s, V v);

        /**
         * Put a value as both a key and a value.
         *
         * @param v The value and key
         * @return True if the Trie had capacity to add the field.
         */
        boolean put(V v);

        /**
         * Remove an entry from the index.
         *
         * @param s The key for the entry
         * @return The removed value of the entry
         */
        V remove(String s);

        /**
         * Remove all entries from the index.
         */
        void clear();

        /**
         * Builder of {@link Index.Mutable} instances. Such builder cannot be
         * directly created, it is instead returned by calling {@link Index.Builder#mutable()}.
         * @param <V> the entry type
         */
        class Builder<V> extends Index.Builder<V>
        {
            private int maxCapacity = -1;

            Builder(boolean caseSensitive, Map<String, V> contents)
            {
                super(caseSensitive, contents);
            }

            /**
             * Configure a maximum capacity for the mutable index.
             * A negative value means there is no capacity limit and
             * the index can grow without limits.
             * The default value is -1.
             * @param capacity the maximum capacity of the index.
             * @return this
             */
            public Builder<V> maxCapacity(int capacity)
            {
                this.maxCapacity = capacity;
                return this;
            }

            /**
             * Build a {@link Mutable} instance.
             * @return a {@link Mutable} instance.
             */
            public Mutable<V> build()
            {
                if (contents != null && maxCapacity == 0)
                    throw new IllegalStateException("Cannot create a mutable index with maxCapacity=0 and some contents");

                // TODO we need to consider large size and alphabet when picking a trie impl
                Mutable<V> result;
                if (maxCapacity > 0)
                {
                    result = new ArrayTernaryTrie<>(!caseSensitive, maxCapacity);
                }
                else if (maxCapacity < 0)
                {
                    if (caseSensitive)
                        result = new ArrayTernaryTrie.Growing<>(false, 512, 512);
                    else
                        result = new TreeTrie<>();
                }
                else
                {
                    result = EmptyTrie.instance(caseSensitive);
                }

                if (contents != null)
                {
                    for (Map.Entry<String, V> entry : contents.entrySet())
                    {
                        if (!result.put(entry.getKey(), entry.getValue()))
                            throw new AssertionError("Index capacity exceeded at " + entry.getKey());
                    }
                }
                return result;
            }
        }
    }

    /**
     * Builder of {@link Index} instances.
     * @param <V> the entry type
     */
    class Builder<V>
    {
        Map<String, V> contents;
        boolean caseSensitive;

        /**
         * Create a new index builder instance.
         */
        public Builder()
        {
            this(false, null);
        }

        Builder(boolean caseSensitive, Map<String, V> contents)
        {
            this.caseSensitive = caseSensitive;
            this.contents = contents;
        }

        private Map<String, V> contents()
        {
            if (contents == null)
                contents = new LinkedHashMap<>();
            return contents;
        }

        /**
         * Configure the index to be either case-sensitive or not.
         * Default value is false.
         *
         * @param caseSensitive true if the index has to be case-sensitive
         * @return this
         */
        public Builder<V> caseSensitive(boolean caseSensitive)
        {
            this.caseSensitive = caseSensitive;
            return this;
        }

        /**
         * Configure some pre-existing entries.
         *
         * @param values an array of values
         * @param keyFunction a {@link Function} that generates the key of each
         * entry of the values array
         * @return this
         */
        public Builder<V> withAll(V[] values, Function<V, String> keyFunction)
        {
            for (V value : values)
            {
                String key = keyFunction.apply(value);
                contents().put(key, value);
            }
            return this;
        }

        /**
         * Configure some pre-existing entries.
         *
         * @param entriesSupplier a {@link Map} {@link Supplier} of entries
         * @return this
         */
        public Builder<V> withAll(Supplier<Map<String, V>> entriesSupplier)
        {
            Map<String, V> map = entriesSupplier.get();
            contents().putAll(map);
            return this;
        }

        /**
         * Configure a pre-existing entry with a key
         * that is the {@link #toString()} representation
         * of the value.
         *
         * @param value The value
         * @return this
         */
        public Builder<V> with(V value)
        {
            contents().put(value.toString(), value);
            return this;
        }

        /**
         * Configure a pre-existing entry.
         *
         * @param key The key
         * @param value The value for the key string
         * @return this
         */
        public Builder<V> with(String key, V value)
        {
            contents().put(key, value);
            return this;
        }

        /**
         * Configure the index to be mutable.
         *
         * @return a {@link Mutable.Builder} configured like this builder.
         */
        public Mutable.Builder<V> mutable()
        {
            return new Mutable.Builder<>(caseSensitive, contents);
        }

        /**
         * Build a {@link Index} instance.
         *
         * @return a {@link Index} instance.
         */
        public Index<V> build()
        {
            if (contents == null)
                return EmptyTrie.instance(caseSensitive);

            // TODO we need to consider large size and alphabet when picking a trie impl
            if (caseSensitive)
                return new ArrayTernaryTrie<>(false, contents);
            else
                return new ArrayTrie<>(contents);
        }
    }
}
