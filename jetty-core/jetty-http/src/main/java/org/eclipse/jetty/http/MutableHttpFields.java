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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * HTTP Fields. A collection of HTTP header and or Trailer fields.
 *
 * <p>This class is not synchronized as it is expected that modifications will only be performed by a
 * single thread.
 *
 * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
 */
class MutableHttpFields implements HttpFields.Mutable
{
    private static final int INITIAL_SIZE = 16;
    private static final int SIZE_INCREMENT = 4;

    private HttpField[] _fields;
    private int _size;

    /**
     * Initialize an empty HttpFields.
     */
    protected MutableHttpFields()
    {
        this(INITIAL_SIZE);  // Based on small sample of Chrome requests.
    }

    /**
     * Initialize an empty HttpFields.
     *
     * @param capacity the capacity of the http fields
     */
    protected MutableHttpFields(int capacity)
    {
        _fields = new HttpField[capacity];
    }

    /**
     * Initialize HttpFields from another.
     *
     * @param fields the fields to copy data from
     */
    protected MutableHttpFields(HttpFields fields)
    {
        add(fields);
    }

    /**
     * Initialize HttpFields from another and replace a field
     *
     * @param fields the fields to copy data from
     * @param replaceField the replacement field
     */
    protected MutableHttpFields(HttpFields fields, HttpField replaceField)
    {
        _fields = new HttpField[fields.size() + SIZE_INCREMENT];
        _size = 0;
        boolean put = false;
        for (HttpField f : fields)
        {
            if (replaceField.isSameName(f))
            {
                if (!put)
                    _fields[_size++] = replaceField;
                put = true;
            }
            else
            {
                _fields[_size++] = f;
            }
        }
        if (!put)
            _fields[_size++] = replaceField;
    }

    /**
     * Initialize HttpFields from another and remove fields
     *
     * @param fields the fields to copy data from
     * @param removeFields the the fields to remove
     */
    protected MutableHttpFields(HttpFields fields, EnumSet<HttpHeader> removeFields)
    {
        _fields = new HttpField[fields.size() + SIZE_INCREMENT];
        _size = 0;
        for (HttpField f : fields)
        {
            if (f.getHeader() == null || !removeFields.contains(f.getHeader()))
                _fields[_size++] = f;
        }
    }

    @Override
    public Mutable add(HttpField field)
    {
        if (field != null)
        {
            if (_fields == null)
                _fields = new HttpField[INITIAL_SIZE];
            if (_size == _fields.length)
                _fields = Arrays.copyOf(_fields, _size + SIZE_INCREMENT);
            _fields[_size++] = field;
        }
        return this;
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        if (_fields == null)
            _fields = new HttpField[fields.size() + SIZE_INCREMENT];
        else if (_size + fields.size() >= _fields.length)
            _fields = Arrays.copyOf(_fields, _size + fields.size() + SIZE_INCREMENT);

        if (fields.size() == 0)
            return this;

        if (fields instanceof org.eclipse.jetty.http.ImmutableHttpFields immutable)
        {
            System.arraycopy(immutable._fields, 0, _fields, _size, immutable._size);
            _size += immutable._size;
        }
        else if (fields instanceof org.eclipse.jetty.http.MutableHttpFields mutable)
        {
            System.arraycopy(mutable._fields, 0, _fields, _size, mutable._size);
            _size += mutable._size;
        }
        else
        {
            for (HttpField f : fields)
            {
                _fields[_size++] = f;
            }
        }
        return this;
    }

    @Override
    public HttpFields asImmutable()
    {
        return new org.eclipse.jetty.http.ImmutableHttpFields(Arrays.copyOf(_fields, _size));
    }

    @Override
    public Mutable clear()
    {
        _size = 0;
        return this;
    }

    @Override
    public int hashCode()
    {
        int hash = 0;
        for (int i = _size; i-- > 0; )
        {
            HttpField field = _fields[i];
            if (field != null)
                hash ^= field.hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof org.eclipse.jetty.http.MutableHttpFields))
            return false;

        return isEqualTo((HttpFields)o);
    }

    /**
     * Get a Field by index.
     *
     * @param index the field index
     * @return A Field value or null if the Field value has not been set
     */
    @Override
    public HttpField getField(int index)
    {
        if (index >= _size || index < 0)
            throw new NoSuchElementException();
        return _fields[index];
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return new Iterator<>()
        {
            int _index = 0;

            @Override
            public boolean hasNext()
            {
                return _index < _size;
            }

            @Override
            public HttpField next()
            {
                return _fields[_index++];
            }

            @Override
            public void remove()
            {
                if (_size == 0)
                    throw new IllegalStateException();
                org.eclipse.jetty.http.MutableHttpFields.this.remove(--_index);
            }
        };
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        return new ListItr();
    }

    @Override
    public Mutable put(HttpField field)
    {
        boolean put = false;

        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f.isSameName(field))
            {
                if (put)
                    System.arraycopy(_fields, i + 1, _fields, i, _size-- - i-- - 1);
                else
                {
                    _fields[i] = field;
                    put = true;
                }
            }
        }
        if (!put)
            add(field);
        return this;
    }

    @Override
    public Mutable put(String name, String value)
    {
        return (value == null)
            ? remove(name)
            : put(new HttpField(name, value));
    }

    @Override
    public Mutable put(HttpHeader header, HttpHeaderValue value)
    {
        return put(header, value.toString());
    }

    @Override
    public Mutable put(HttpHeader header, String value)
    {
        return (value == null)
            ? remove(header)
            : put(new HttpField(header, value));
    }

    @Override
    public Mutable put(String name, List<String> list)
    {
        Objects.requireNonNull(name);
        Objects.requireNonNull(list);
        remove(name);
        for (String v : list)
        {
            if (v != null)
                add(name, v);
        }
        return this;
    }

    @Override
    public Mutable computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
    {
        return computeField(header, computeFn, (f, h) -> f.getHeader() == h);
    }

    @Override
    public Mutable computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
    {
        return computeField(name, computeFn, HttpField::is);
    }

    public <T> Mutable computeField(T header, BiFunction<T, List<HttpField>, HttpField> computeFn, BiPredicate<HttpField, T> matcher)
    {
        // Look for first occurrence
        int first = -1;
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (matcher.test(f, header))
            {
                first = i;
                break;
            }
        }

        // If the header is not found, add a new one;
        if (first < 0)
        {
            HttpField newField = computeFn.apply(header, null);
            if (newField != null)
                add(newField);
            return this;
        }

        // Are there any more occurrences?
        List<HttpField> found = null;
        for (int i = first + 1; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (matcher.test(f, header))
            {
                if (found == null)
                {
                    found = new ArrayList<>();
                    found.add(_fields[first]);
                }
                // Remember and remove additional fields
                found.add(f);
                remove(i--);
            }
        }

        // If no additional fields were found, handle singleton case
        if (found == null)
            found = Collections.singletonList(_fields[first]);
        else
            found = Collections.unmodifiableList(found);

        HttpField newField = computeFn.apply(header, found);
        if (newField == null)
            remove(first);
        else
            _fields[first] = newField;
        return this;
    }

    @Override
    public Mutable remove(HttpHeader name)
    {
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f.getHeader() == name)
                remove(i--);
        }
        return this;
    }

    @Override
    public Mutable remove(EnumSet<HttpHeader> headers)
    {
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (headers.contains(f.getHeader()))
                remove(i--);
        }
        return this;
    }

    @Override
    public Mutable remove(String name)
    {
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f.is(name))
                remove(i--);
        }
        return this;
    }

    private void remove(int i)
    {
        _size--;
        System.arraycopy(_fields, i + 1, _fields, i, _size - i);
        _fields[_size] = null;
    }

    public int size()
    {
        return _size;
    }

    @Override
    public Stream<HttpField> stream()
    {
        return Arrays.stream(_fields, 0, _size);
    }

    @Override
    public String toString()
    {
        return asString();
    }

    private class ListItr implements ListIterator<HttpField>
    {
        int _cursor;       // index of next element to return
        int _current = -1;

        @Override
        public void add(HttpField field)
        {
            if (field == null)
                return;

            int last = _size++;
            if (_fields.length < _size)
                _fields = Arrays.copyOf(_fields, _fields.length + SIZE_INCREMENT);
            System.arraycopy(_fields, _cursor, _fields, _cursor + 1, last - _cursor);
            _fields[_cursor++] = field;
            _current = -1;
        }

        @Override
        public boolean hasNext()
        {
            return _cursor != _size;
        }

        @Override
        public boolean hasPrevious()
        {
            return _cursor > 0;
        }

        @Override
        public HttpField next()
        {
            if (_cursor == _size)
                throw new NoSuchElementException();
            _current = _cursor++;
            return _fields[_current];
        }

        @Override
        public int nextIndex()
        {
            return _cursor + 1;
        }

        @Override
        public HttpField previous()
        {
            if (_cursor == 0)
                throw new NoSuchElementException();
            _current = --_cursor;
            return _fields[_current];
        }

        @Override
        public int previousIndex()
        {
            return _cursor - 1;
        }

        @Override
        public void remove()
        {
            if (_current < 0)
                throw new IllegalStateException();
            org.eclipse.jetty.http.MutableHttpFields.this.remove(_current);
            _cursor = _current;
            _current = -1;
        }

        @Override
        public void set(HttpField field)
        {
            if (_current < 0)
                throw new IllegalStateException();
            if (field == null)
                remove();
            else
                _fields[_current] = field;
        }
    }
}
