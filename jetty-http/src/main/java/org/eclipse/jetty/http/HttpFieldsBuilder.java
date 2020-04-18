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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
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
public class HttpFieldsBuilder implements Iterable<HttpField>, HttpFields
{
    ArrayList<HttpField> _fields;

    /**
     * Initialize an empty HttpFields.
     */
    protected HttpFieldsBuilder()
    {
        this(16);  // Based on small sample of Chrome requests.
    }

    /**
     * Initialize an empty HttpFields.
     *
     * @param capacity the capacity of the http fields
     */
    HttpFieldsBuilder(int capacity)
    {
        _fields = new ArrayList<>(capacity);
    }

    /**
     * Initialize HttpFields from another.
     *
     * @param fields the fields to copy data from
     */
    HttpFieldsBuilder(HttpFields fields)
    {
        if (fields instanceof Immutable)
            _fields = new ArrayList<>(Arrays.asList(((Immutable)fields)._fields));
        else if (fields instanceof HttpFieldsBuilder)
            _fields = new ArrayList<>(((HttpFieldsBuilder)fields)._fields);
        else
        {
            _fields = new ArrayList<>(fields.size() + 4);
            for (HttpField f : fields)
                _fields.add(f);
        }
    }

    /**
     * Initialize HttpFields from another and replace a field
     *
     * @param fields the fields to copy data from
     * @param replaceField the replacement field
     */
    HttpFieldsBuilder(HttpFields fields, HttpField replaceField)
    {
        _fields = new ArrayList<>(fields.size() + 4);
        boolean put = false;
        for (HttpField f : fields)
        {
            if (replaceField.isSameName(f))
            {
                if (!put)
                    _fields.add(replaceField);
                put = true;
            }
            else
            {
                _fields.add(f);
            }
        }
        if (!put)
            _fields.add(replaceField);
    }

    /**
     * Initialize HttpFields from another and remove fields
     *
     * @param fields the fields to copy data from
     * @param removeFields the the fields to remove
     */
    HttpFieldsBuilder(HttpFields fields, EnumSet<HttpHeader> removeFields)
    {
        _fields = new ArrayList<>(fields.size());
        for (HttpField f : fields)
        {
            if (f.getHeader() == null || removeFields.contains(f.getHeader()))
                _fields.add(f);
        }
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param name the name of the field
     * @param value the value of the field.
     * @return this builder
     */
    public HttpFieldsBuilder add(String name, String value)
    {
        if (value != null)
            return add(new HttpField(name, value));
        return this;
    }

    public HttpFieldsBuilder add(HttpHeader header, HttpHeaderValue value)
    {
        return add(header, value.toString());
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param header the header
     * @param value the value of the field.
     * @return this builder
     */
    public HttpFieldsBuilder add(HttpHeader header, String value)
    {
        if (value == null)
            throw new IllegalArgumentException("null value");

        HttpField field = new HttpField(header, value);
        return add(field);
    }

    public HttpFieldsBuilder add(HttpField field)
    {
        if (field != null)
            _fields.add(field);
        return this;
    }

    /**
     * Add fields from another HttpFields instance. Single valued fields are replaced, while all
     * others are added.
     *
     * @param fields the fields to add
     * @return this builder
     */
    public HttpFieldsBuilder add(HttpFields fields)
    {
        // TODO is this any different to addAll?

        if (fields == null)
            return this;

        _fields.ensureCapacity(size() + fields.size() + 4);
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
        return this;
    }

    public HttpFieldsBuilder addAll(HttpFields fields)
    {
        _fields.ensureCapacity(size() + fields.size() + 4);
        for (HttpField f : fields)
            _fields.add(f);
        return this;
    }

    /**
     * Add comma separated values, but only if not already
     * present.
     *
     * @param header The header to add the value(s) to
     * @param values The value(s) to add
     * @return this builder
     */
    public HttpFieldsBuilder addCSV(HttpHeader header, String... values)
    {
        // TODO is the javadoc right ?
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = addCSV(existing, values);
        if (value != null)
            add(header, value);
        return this;
    }

    /**
     * Add comma separated values, but only if not already
     * present.
     *
     * @param name The header to add the value(s) to
     * @param values The value(s) to add
     * @return this builder
     */
    public HttpFieldsBuilder addCSV(String name, String... values)
    {
        // TODO is the javadoc right ?
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = addCSV(existing, values);
        if (value != null)
            add(name, value);
        return this;
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     * @return this builder
     */
    public HttpFieldsBuilder addDateField(String name, long date)
    {
        add(name, DateGenerator.formatDate(date));
        return this;
    }

    @Override
    public HttpFields asImmutable()
    {
        return new Immutable(_fields.toArray(new HttpField[0]));
    }

    public HttpFieldsBuilder clear()
    {
        _fields.clear();
        return this;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof HttpFieldsBuilder))
            return false;

        return isEqualTo((HttpFields)o);
    }

    /**
     * Remove the first instance of a header and return previous value
     *
     * @param name the field to remove
     * @return the header that was removed
     */
    public HttpField getAndRemove(HttpHeader name)
    {
        for (ListIterator<HttpField> i = listIterator(); i.hasNext(); )
        {
            HttpField f = i.next();
            if (f.getHeader() == name)
            {
                i.remove();
                return f;
            }
        }
        return null;
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
        if (index >= size())
            throw new NoSuchElementException();
        return _fields.get(index);
    }

    @Override
    public int hashCode()
    {
        return asHashCode();
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return _fields.iterator();
    }

    public ListIterator<HttpField> listIterator()
    {
        return _fields.listIterator();
    }

    public HttpFieldsBuilder put(HttpField field)
    {
        boolean put = false;
        for (ListIterator<HttpField> i = listIterator(); i.hasNext(); )
        {
            HttpField f = i.next();
            if (f.isSameName(field))
            {
                if (put)
                    i.remove();
                else
                {
                    i.set(field);
                    put = true;
                }
            }
        }
        if (!put)
            add(field);
        return this;
    }

    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     * @return this builder
     */
    public HttpFieldsBuilder put(String name, String value)
    {
        return (value == null)
            ? remove(name)
            : put(new HttpField(name, value));
    }

    public HttpFieldsBuilder put(HttpHeader header, HttpHeaderValue value)
    {
        return put(header, value.toString());
    }

    /**
     * Set a field.
     *
     * @param header the header name of the field
     * @param value the value of the field. If null the field is cleared.
     * @return this builder
     */
    public HttpFieldsBuilder put(HttpHeader header, String value)
    {
        return (value == null)
            ? remove(header)
            : put(new HttpField(header, value));
    }

    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param list the List value of the field. If null the field is cleared.
     * @return this builder
     */
    public HttpFieldsBuilder put(String name, List<String> list)
    {
        remove(name);
        for (String v : list)
        {
            if (v != null)
                add(name, v);
        }
        return this;
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     * @return this builder
     */
    public HttpFieldsBuilder putDateField(HttpHeader name, long date)
    {
        return put(name, DateGenerator.formatDate(date));
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     * @return this builder
     */
    public HttpFieldsBuilder putDateField(String name, long date)
    {
        return put(name, DateGenerator.formatDate(date));
    }

    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     * @return this builder
     */
    public HttpFieldsBuilder putLongField(HttpHeader name, long value)
    {
        return put(name, Long.toString(value));
    }

    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     * @return this builder
     */
    public HttpFieldsBuilder putLongField(String name, long value)
    {
        return put(name, Long.toString(value));
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return this builder
     */
    public HttpFieldsBuilder remove(HttpHeader name)
    {
        for (ListIterator<HttpField> i = listIterator(); i.hasNext(); )
        {
            HttpField f = i.next();
            if (f.getHeader() == name)
                i.remove();
        }
        return this;
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return this builder
     */
    public HttpFieldsBuilder remove(String name)
    {
        for (ListIterator<HttpField> i = listIterator(); i.hasNext(); )
        {
            HttpField f = i.next();
            if (f.getName().equalsIgnoreCase(name))
                i.remove();
        }
        return this;
    }

    public int size()
    {
        return _fields.size();
    }

    @Override
    public Stream<HttpField> stream()
    {
        return _fields.stream();
    }

    @Override
    public String toString()
    {
        return asString();
    }

    private String addCSV(QuotedCSV existing, String... values)
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
    private static class Immutable implements HttpFields
    {
        final HttpField[] _fields;

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
        public HttpFields asImmutable()
        {
            return this;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof Immutable))
                return false;

            return isEqualTo((HttpFields)o);
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
            return Arrays.stream(_fields).iterator();
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
}
