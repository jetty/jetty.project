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
    private boolean _immutable;
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
        if (fields instanceof ImmutableHttpFields immutable)
        {
            _immutable = true;
            _fields = immutable._fields;
            _size = immutable._size;
        }
        else if (fields != null)
        {
            _fields = new HttpField[fields.size() + SIZE_INCREMENT];
            add(fields);
        }
        else
        {
            _fields = new HttpField[INITIAL_SIZE];
        }
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
            if (_immutable || _size == _fields.length)
            {
                _immutable = false;
                _fields = Arrays.copyOf(_fields, _size + SIZE_INCREMENT);
            }
            _fields[_size++] = field;
        }
        return this;
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        if (fields.size() == 0)
            return this;

        if (_immutable || _size + fields.size() >= _fields.length)
        {
            _immutable = false;
            _fields = Arrays.copyOf(_fields, _size + fields.size() + SIZE_INCREMENT);
        }

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
        _immutable = true;
        return new ImmutableHttpFields(_fields, _size);
    }

    private void copyImmutable()
    {
        if (_immutable)
        {
            _immutable = false;
            _fields = Arrays.copyOf(_fields, _fields.length);
        }
    }

    @Override
    public Mutable clear()
    {
        if (_immutable)
        {
            _fields = new HttpField[_fields.length];
            _immutable = false;
        }
        _size = 0;
        return this;
    }

    @Override
    public int hashCode()
    {
        int hash = 2099; // prime
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
    public HttpField getField(HttpHeader header)
    {
        // default impl overridden for efficiency
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f != null && f.getHeader() == header)
                return f;
        }
        return null;
    }

    @Override
    public HttpField getField(String name)
    {
        // default impl overridden for efficiency
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f != null && f.is(name))
                return f;
        }
        return null;
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
        return listIterator(0);
    }

    @Override
    public ListIterator<HttpField> listIterator(int index)
    {
        copyImmutable();
        return new Listerator(index);
    }

    @Override
    public Mutable put(HttpField field)
    {
        copyImmutable();
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
        copyImmutable();
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
        if (_immutable)
        {
            _immutable = false;
            HttpField[] fields = _fields;
            _fields = new HttpField[fields.length];
            System.arraycopy(fields, 0, _fields, 0, i);
            System.arraycopy(fields, i + 1, _fields, i, _size - i);
        }
        else
        {
            System.arraycopy(_fields, i + 1, _fields, i, _size - i);
        }
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
            if (field == null)
                return;

            int last = _size++;
            if (_fields.length < _size)
                _fields = Arrays.copyOf(_fields, _fields.length + SIZE_INCREMENT);
            System.arraycopy(_fields, _index, _fields, _index + 1, last - _index);
            _fields[_index++] = field;
            _last = -1;
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
            if (_last < 0)
                throw new IllegalStateException();
            org.eclipse.jetty.http.MutableHttpFields.this.remove(_last);
            _index = _last;
            _last = -1;
        }

        @Override
        public void set(HttpField field)
        {
            if (_last < 0)
                throw new IllegalStateException();
            if (field == null)
                remove();
            else
                _fields[_last] = field;
        }
    }
}
