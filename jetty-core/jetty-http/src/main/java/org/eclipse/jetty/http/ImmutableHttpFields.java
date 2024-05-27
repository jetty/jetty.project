//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * An immutable implementation of {@link HttpFields}.
 */
class ImmutableHttpFields implements HttpFields
{
    final HttpField[] _fields;
    final int _size;
    RandomAccess _randomAccess;

    /**
     * Initialize HttpFields from copy.
     *
     * @param fields the fields to copy data from
     */
    protected ImmutableHttpFields(HttpField[] fields)
    {
        this(fields, fields.length);
    }

    protected ImmutableHttpFields(HttpField[] fields, int size)
    {
        _fields = fields;
        _size = size;
    }

    @Override
    public HttpFields asImmutable()
    {
        return this;
    }

    @Override
    public HttpFields asRandomAccess()
    {
        if (_randomAccess == null)
            _randomAccess = new RandomAccess(this);
        return _randomAccess;
    }

    @Override
    public int hashCode()
    {
        int hash = 1993; // prime
        for (int i = _size; i-- > 0; )
        {
            hash ^= _fields[i].hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o instanceof HttpFields httpFields)
            return isEqualTo(httpFields);
        return false;
    }

    @Override
    public String get(String name)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.is(name))
                return f.getValue();
        }
        return null;
    }

    @Override
    public String get(HttpHeader header)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.getHeader() == header)
                return f.getValue();
        }
        return null;
    }

    @Override
    public HttpField getField(HttpHeader header)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.getHeader() == header)
                return f;
        }
        return null;
    }

    @Override
    public HttpField getField(String name)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.is(name))
                return f;
        }
        return null;
    }

    @Override
    public HttpField getField(int index)
    {
        if (index >= _size)
            throw new NoSuchElementException();
        return _fields[index];
    }

    @Override
    public ListIterator<HttpField> listIterator(int index)
    {
        return new Listerator(index);
    }

    @Override
    public int size()
    {
        return _size;
    }

    @Override
    public Stream<HttpField> stream()
    {
        return Arrays.stream(_fields).filter(Objects::nonNull);
    }

    @Override
    public String toString()
    {
        return asString();
    }

    private class Listerator implements ListIterator<HttpField>
    {
        private int _index;
        private int _last = -1;

        Listerator(int index)
        {
            if (index < 0 || index > _size)
                throw new NoSuchElementException(Integer.toString(index));
            _index = index;
        }

        @Override
        public void add(HttpField field)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasNext()
        {
            return _index < _size;
        }

        @Override
        public boolean hasPrevious()
        {
            return _index > 0;
        }

        @Override
        public HttpField next()
        {
            if (_index >= _size)
                throw new NoSuchElementException(Integer.toString(_index));
            _last = _index++;
            return _fields[_last];
        }

        @Override
        public int nextIndex()
        {
            return _index + 1;
        }

        @Override
        public HttpField previous()
        {
            if (_index <= 0)
                throw new NoSuchElementException(Integer.toString(_index - 1));
            _last = --_index;
            return _fields[_last];
        }

        @Override
        public int previousIndex()
        {
            return _index - 1;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(HttpField field)
        {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * An immutable {@link HttpFields} instance, optimized for random field access.
     */
    private static class RandomAccess implements HttpFields
    {
        private final HttpFields _httpFields;
        private final EnumMap<HttpHeader, HttpField> _enumMap = new EnumMap<>(HttpHeader.class);
        private final Map<String, HttpField> _stringMap;

        RandomAccess(org.eclipse.jetty.http.ImmutableHttpFields httpFields)
        {
            _httpFields = Objects.requireNonNull(httpFields);
            Map<String, HttpField> stringMap = null;
            for (HttpField field : httpFields)
            {
                HttpHeader header = field.getHeader();
                if (header != null)
                {
                    _enumMap.putIfAbsent(header, field);
                }
                else
                {
                    if (stringMap == null)
                        stringMap = new TreeMap<>(String::compareToIgnoreCase);
                    stringMap.putIfAbsent(field.getName(), field);
                }
            }
            _stringMap = stringMap == null ? Collections.emptyMap() : stringMap;
        }

        @Override
        public HttpFields asImmutable()
        {
            return this;
        }

        @Override
        public String get(HttpHeader header)
        {
            HttpField field = _enumMap.get(header);
            return field == null ? null : field.getValue();
        }

        @Override
        public String get(String name)
        {
            HttpField field = _stringMap.get(name);
            if (field == null)
                field = _enumMap.get(HttpHeader.CACHE.get(name));
            return field == null ? null : field.getValue();
        }

        @Override
        public boolean contains(HttpHeader header)
        {
            return _enumMap.containsKey(header);
        }

        @Override
        public boolean contains(EnumSet<HttpHeader> headers)
        {
            for (HttpHeader header : headers)
                if (_enumMap.containsKey(header))
                    return true;
            return false;
        }

        @Override
        public boolean contains(String name)
        {
            return _stringMap.containsKey(name) || _enumMap.containsKey(HttpHeader.CACHE.get(name));
        }

        @Override
        public HttpField getField(HttpHeader header)
        {
            return _enumMap.get(header);
        }

        @Override
        public HttpField getField(String name)
        {
            HttpField field = _stringMap.get(name);
            return field == null ? _enumMap.get(HttpHeader.CACHE.get(name)) : field;
        }

        @Override
        public Iterator<HttpField> iterator()
        {
            return _httpFields.iterator();
        }

        @Override
        public ListIterator<HttpField> listIterator()
        {
            return _httpFields.listIterator();
        }

        @Override
        public int size()
        {
            return _httpFields.size();
        }

        @Override
        public ListIterator<HttpField> listIterator(int index)
        {
            return _httpFields.listIterator(index);
        }

        @Override
        public Set<String> getFieldNamesCollection()
        {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            TreeSet<String> seenByName = null;
            for (HttpField field : _httpFields)
            {
                HttpHeader header = field.getHeader();
                if (_enumMap.containsKey(header))
                    set.add(header.asString());
                else if (_stringMap.containsKey(field.getName()))
                {
                    if (seenByName == null)
                        seenByName = new TreeSet<>(String::compareToIgnoreCase);
                    if (seenByName.add(field.getName()))
                        set.add(field.getName());
                }
            }
            return set;
        }
    }
}
