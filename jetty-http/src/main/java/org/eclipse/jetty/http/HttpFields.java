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

package org.eclipse.jetty.http;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * HTTP Fields. A collection of HTTP header and or Trailer fields.
 *
 * <p>This class is not synchronized as it is expected that modifications will only be performed by a
 * single thread.
 *
 * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
 */
public class HttpFields implements Iterable<HttpField>, HttpFieldList
{
    HttpField[] _fields;
    int _size;

    /**
     * Initialize an empty HttpFields.
     */
    public HttpFields()
    {
        this(16);  // Based on small sample of Chrome requests.
    }

    /**
     * Initialize an empty HttpFields.
     *
     * @param capacity the capacity of the http fields
     */
    public HttpFields(int capacity)
    {
        _fields = new HttpField[capacity];
    }

    /**
     * Initialize HttpFields from copy.
     *
     * @param fields the fields to copy data from
     */
    public HttpFields(HttpFields fields)
    {
        _fields = Arrays.copyOf(fields._fields, fields._fields.length);
        _size = fields._size;
    }

    /**
     * Get field value without parameters. Some field values can have parameters. This method separates the
     * value from the parameters and optionally populates a map with the parameters. For example:
     *
     * <PRE>
     *
     * FieldName : Value ; param1=val1 ; param2=val2
     *
     * </PRE>
     *
     * @param value The Field value, possibly with parameters.
     * @return The value.
     */
    public static String stripParameters(String value)
    {
        if (value == null)
            return null;

        int i = value.indexOf(';');
        if (i < 0)
            return value;
        return value.substring(0, i).trim();
    }

    public static String valueParameters(String value, Map<String, String> parameters)
    {
        return HttpField.getValueParameters(value, parameters);
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param name the name of the field
     * @param value the value of the field.
     */
    public void add(String name, String value)
    {
        if (value == null)
            return;

        HttpField field = new HttpField(name, value);
        add(field);
    }

    public void add(HttpHeader header, HttpHeaderValue value)
    {
        add(header, value.toString());
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param header the header
     * @param value the value of the field.
     */
    public void add(HttpHeader header, String value)
    {
        if (value == null)
            throw new IllegalArgumentException("null value");

        HttpField field = new HttpField(header, value);
        add(field);
    }

    public void add(HttpField field)
    {
        if (field != null)
        {
            if (_size == _fields.length)
                _fields = Arrays.copyOf(_fields, _size * 2);
            _fields[_size++] = field;
        }
    }

    /**
     * Add fields from another HttpFields instance. Single valued fields are replaced, while all
     * others are added.
     *
     * @param fields the fields to add
     */
    public void add(HttpFields fields)
    {
        if (fields == null)
            return;

        Enumeration<String> e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            String name = e.nextElement();
            Enumeration<String> values = fields.getValues(name);
            while (values.hasMoreElements())
            {
                add(name, values.nextElement());
            }
        }
    }

    public void addAll(HttpFields fields)
    {
        for (int i = 0; i < fields._size; i++)
        {
            add(fields._fields[i]);
        }
    }

    /**
     * Add comma separated values, but only if not already
     * present.
     *
     * @param header The header to add the value(s) to
     * @param values The value(s) to add
     * @return True if headers were modified
     */
    public boolean addCSV(HttpHeader header, String... values)
    {
        QuotedCSV existing = null;
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f.getHeader() == header)
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }

        String value = addCSV(existing, values);
        if (value != null)
        {
            add(header, value);
            return true;
        }
        return false;
    }

    /**
     * Add comma separated values, but only if not already
     * present.
     *
     * @param name The header to add the value(s) to
     * @param values The value(s) to add
     * @return True if headers were modified
     */
    public boolean addCSV(String name, String... values)
    {
        QuotedCSV existing = null;
        for (int i = 0; i < _size; i++)
        {
            HttpField f = _fields[i];
            if (f.getName().equalsIgnoreCase(name))
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = addCSV(existing, values);
        if (value != null)
        {
            add(name, value);
            return true;
        }
        return false;
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void addDateField(String name, long date)
    {
        String d = DateGenerator.formatDate(date);
        add(name, d);
    }

    public HttpFieldList asImmutable()
    {
        return new HttpFields.Immutable(Arrays.copyOf(_fields, _size));
    }

    public void clear()
    {
        _size = 0;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof HttpFields))
            return false;

        return isEqualTo((HttpFieldList)o);
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
        if (index >= _size)
            throw new NoSuchElementException();
        return _fields[index];
    }

    @Override
    public int hashCode()
    {
        return asHashCode();
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
                if (hasNext())
                    return _fields[_index++];
                throw new NoSuchElementException();
            }
        };
    }

    public ListIterator<HttpField> listIterator()
    {
        return new ListItr();
    }

    public void put(HttpField field)
    {
        boolean put = false;
        for (int i = _size; i-- > 0; )
        {
            HttpField f = _fields[i];
            if (f.isSameName(field))
            {
                if (put)
                {
                    System.arraycopy(_fields, i + 1, _fields, i, --_size - i);
                }
                else
                {
                    _fields[i] = field;
                    put = true;
                }
            }
        }
        if (!put)
            add(field);
    }

    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(String name, String value)
    {
        if (value == null)
            remove(name);
        else
            put(new HttpField(name, value));
    }

    public void put(HttpHeader header, HttpHeaderValue value)
    {
        put(header, value.toString());
    }

    /**
     * Set a field.
     *
     * @param header the header name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(HttpHeader header, String value)
    {
        if (value == null)
            remove(header);
        else
            put(new HttpField(header, value));
    }

    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param list the List value of the field. If null the field is cleared.
     */
    public void put(String name, List<String> list)
    {
        remove(name);
        for (String v : list)
        {
            if (v != null)
                add(name, v);
        }
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(HttpHeader name, long date)
    {
        String d = DateGenerator.formatDate(date);
        put(name, d);
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(String name, long date)
    {
        String d = DateGenerator.formatDate(date);
        put(name, d);
    }

    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(HttpHeader name, long value)
    {
        String v = Long.toString(value);
        put(name, v);
    }

    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(String name, long value)
    {
        String v = Long.toString(value);
        put(name, v);
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return the header that was removed
     */
    public HttpField remove(HttpHeader name)
    {
        HttpField removed = null;
        for (int i = _size; i-- > 0; )
        {
            HttpField f = _fields[i];
            if (f.getHeader() == name)
            {
                removed = f;
                System.arraycopy(_fields, i + 1, _fields, i, --_size - i);
            }
        }
        return removed;
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return the header that was removed
     */
    public HttpField remove(String name)
    {
        HttpField removed = null;
        for (int i = _size; i-- > 0; )
        {
            HttpField f = _fields[i];
            if (f.getName().equalsIgnoreCase(name))
            {
                removed = f;
                System.arraycopy(_fields, i + 1, _fields, i, --_size - i);
            }
        }
        return removed;
    }

    public int size()
    {
        return _size;
    }

    @Override
    public Stream<HttpField> stream()
    {
        return Arrays.stream(_fields).limit(_size);
    }

    @Override
    public String toString()
    {
        return asString();
    }

    protected String addCSV(QuotedCSV existing, String... values)
    {
        // remove any existing values from the new values
        boolean add = true;
        if (existing != null && !existing.isEmpty())
        {
            add = false;

            for (int i = values.length; i-- > 0; )
            {
                String unquoted = QuotedCSV.unquote(values[i]);
                if (existing.getValues().contains(unquoted))
                    values[i] = null;
                else
                    add = true;
            }
        }

        if (add)
        {
            StringBuilder value = new StringBuilder();
            for (String v : values)
            {
                if (v == null)
                    continue;
                if (value.length() > 0)
                    value.append(", ");
                value.append(v);
            }
            if (value.length() > 0)
                return value.toString();
        }

        return null;
    }

    /**
     * HTTP Fields. A collection of HTTP header and or Trailer fields.
     *
     * <p>This class is not synchronized as it is expected that modifications will only be performed by a
     * single thread.
     *
     * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
     */
    private static class Immutable implements HttpFieldList
    {
        private final HttpField[] _fields;

        /**
         * Initialize HttpFields from copy.
         *
         * @param fields the fields to copy data from
         */
        Immutable(HttpField[] fields)
        {
            _fields = fields;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof Immutable))
                return false;

            return isEqualTo((HttpFieldList)o);
        }

        @Override
        public HttpField getField(int index)
        {
            if (index >= _fields.length)
                throw new NoSuchElementException();
            return _fields[index];
        }

        @Override
        public int hashCode()
        {
            return asHashCode();
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
                    return _index < _fields.length;
                }

                @Override
                public HttpField next()
                {
                    if (_index < _fields.length)
                        return _fields[_index++];
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public int size()
        {
            return _fields.length;
        }

        @Override
        public Stream<HttpField> stream()
        {
            return Arrays.stream(_fields);
        }

        @Override
        public String toString()
        {
            return asString();
        }
    }

    private class ListItr implements ListIterator<HttpField>
    {
        int _cursor;       // index of next element to return
        int _current = -1;

        @Override
        public void add(HttpField field)
        {
            _fields = Arrays.copyOf(_fields, _fields.length + 1);
            System.arraycopy(_fields, _cursor, _fields, _cursor + 1, _size++);
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
            _size--;
            System.arraycopy(_fields, _current + 1, _fields, _current, _size - _current);
            _fields[_size] = null;
            _cursor = _current;
            _current = -1;
        }

        @Override
        public void set(HttpField field)
        {
            if (_current < 0)
                throw new IllegalStateException();
            _fields[_current] = field;
        }
    }
}
