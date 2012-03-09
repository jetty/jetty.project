/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p>A container for name/value pairs, known as headers.</p>
 * <p>A {@link Header} is composed of a case-insensitive name string and
 * of a case-sensitive set of value strings.</p>
 * <p>The implementation of this class is not thread safe.</p>
 */
public class Headers implements Iterable<Headers.Header>
{
    private final Map<String, Header> headers;

    /**
     * <p>Creates an empty modifiable {@link Headers} instance.</p>
     * @see #Headers(Headers, boolean)
     */
    public Headers()
    {
        headers = new LinkedHashMap<>();
    }

    /**
     * <p>Creates a {@link Headers} instance by copying the headers from the given
     * {@link Headers} and making it (im)mutable depending on the given {@code immutable} parameter</p>
     *
     * @param original the {@link Headers} to copy headers from
     * @param immutable whether this instance is immutable
     */
    public Headers(Headers original, boolean immutable)
    {
        Map<String, Header> copy = new LinkedHashMap<>();
        copy.putAll(original.headers);
        headers = immutable ? Collections.unmodifiableMap(copy) : copy;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Headers that = (Headers)obj;
        return headers.equals(that.headers);
    }

    @Override
    public int hashCode()
    {
        return headers.hashCode();
    }

    /**
     * @return a set of header names
     */
    public Set<String> names()
    {
        Set<String> result = new LinkedHashSet<>();
        for (Header header : headers.values())
            result.add(header.name);
        return result;
    }

    /**
     * @param name the header name
     * @return the {@link Header} with the given name, or null if no such header exists
     */
    public Header get(String name)
    {
        return headers.get(name.trim().toLowerCase());
    }

    /**
     * <p>Inserts or replaces the given name/value pair as a single-valued {@link Header}.</p>
     *
     * @param name the header name
     * @param value the header value
     */
    public void put(String name, String value)
    {
        name = name.trim();
        Header header = new Header(name, value.trim());
        headers.put(name.toLowerCase(), header);
    }

    /**
     * <p>Inserts or replaces the given {@link Header}, mapped to the {@link Header#name() header's name}</p>
     *
     * @param header the header to add
     */
    public void put(Header header)
    {
        headers.put(header.name().toLowerCase(), header);
    }

    /**
     * <p>Adds the given value to a header with the given name, creating a {@link Header} is none exists
     * for the given name.</p>
     *
     * @param name the header name
     * @param value the header value to add
     */
    public void add(String name, String value)
    {
        name = name.trim();
        Header header = headers.get(name.toLowerCase());
        if (header == null)
        {
            header = new Header(name, value.trim());
            headers.put(name.toLowerCase(), header);
        }
        else
        {
            header = new Header(header.name(), header.value() + "," + value.trim());
            headers.put(name.toLowerCase(), header);
        }
    }

    /**
     * <p>Removes the {@link Header} with the given name</p>
     *
     * @param name the name of the header to remove
     * @return the removed header, or null if no such header existed
     */
    public Header remove(String name)
    {
        name = name.trim();
        return headers.remove(name.toLowerCase());
    }

    /**
     * <p>Empties this {@link Headers} instance from all headers</p>
     * @see #isEmpty()
     */
    public void clear()
    {
        headers.clear();
    }

    /**
     * @return whether this {@link Headers} instance is empty
     */
    public boolean isEmpty()
    {
        return headers.isEmpty();
    }

    /**
     * @return the number of headers
     */
    public int size()
    {
        return headers.size();
    }

    /**
     * @return an iterator over the {@link Header} present in this instance
     */
    @Override
    public Iterator<Header> iterator()
    {
        return headers.values().iterator();
    }

    @Override
    public String toString()
    {
        return headers.toString();
    }

    /**
     * <p>A named list of string values.</p>
     * <p>The name is case-sensitive and there must be at least one value.</p>
     */
    public static class Header
    {
        private final String name;
        private final String[] values;

        private Header(String name, String value, String... values)
        {
            this.name = name;
            this.values = new String[values.length + 1];
            this.values[0] = value;
            if (values.length > 0)
                System.arraycopy(values, 0, this.values, 1, values.length);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Header that = (Header)obj;
            return name.equals(that.name) && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode()
        {
            int result = name.hashCode();
            result = 31 * result + Arrays.hashCode(values);
            return result;
        }

        /**
         * @return the header's name
         */
        public String name()
        {
            return name;
        }

        /**
         * @return the first header's value
         */
        public String value()
        {
            return values[0];
        }

        /**
         * <p>Attempts to convert the result of {@link #value()} to an integer,
         * returning it if the conversion is successful; returns null if the
         * result of {@link #value()} is null.</p>
         *
         * @return the result of {@link #value()} converted to an integer, or null
         * @throws NumberFormatException if the conversion fails
         */
        public Integer valueAsInt()
        {
            final String value = value();
            return value == null ? null : Integer.valueOf(value);
        }

        /**
         * @return the header's values
         */
        public String[] values()
        {
            return values;
        }

        /**
         * @return whether the header has multiple values
         */
        public boolean hasMultipleValues()
        {
            return values.length > 1;
        }

        @Override
        public String toString()
        {
            return Arrays.toString(values);
        }
    }
}
