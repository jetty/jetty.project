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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface that represents readonly list of {@link HttpField}s.
 */
public interface HttpFields extends Iterable<HttpField>
{
    HttpFields EMPTY = build().asImmutable();

    static HttpFieldsBuilder build()
    {
        return new HttpFieldsBuilder();
    }

    static HttpFieldsBuilder build(int capacity)
    {
        return new HttpFieldsBuilder(capacity);
    }

    static HttpFieldsBuilder build(HttpFields fields)
    {
        return new HttpFieldsBuilder(fields);
    }

    static HttpFieldsBuilder build(HttpFields fields, HttpField replaceField)
    {
        return new HttpFieldsBuilder(fields, replaceField);
    }

    static HttpFieldsBuilder build(HttpFields fields, EnumSet<HttpHeader> removeFields)
    {
        return new HttpFieldsBuilder(fields, removeFields);
    }

    default int asHashCode()
    {
        int hash = 0;
        for (HttpField f : this)
            hash ^= f.hashCode();
        return hash;
    }

    HttpFields asImmutable();

    default HttpFieldsBuilder asMutable()
    {
        return build(this);
    }

    default String asString()
    {
        StringBuilder buffer = new StringBuilder();
        for (HttpField field : this)
        {
            if (field != null)
            {
                String tmp = field.getName();
                if (tmp != null)
                    buffer.append(tmp);
                buffer.append(": ");
                tmp = field.getValue();
                if (tmp != null)
                    buffer.append(tmp);
                buffer.append("\r\n");
            }
        }
        buffer.append("\r\n");
        return buffer.toString();
    }

    default boolean contains(HttpField field)
    {
        for (HttpField f : this)
        {
            if (f.isSameName(field) && (f.equals(field) || f.contains(field.getValue())))
                return true;
        }
        return false;
    }

    default boolean contains(HttpHeader header, String value)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header && f.contains(value))
                return true;
        }
        return false;
    }

    default boolean contains(String name, String value)
    {
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name) && f.contains(value))
                return true;
        }
        return false;
    }

    default boolean contains(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return true;
        }
        return false;
    }

    default boolean contains(EnumSet<HttpHeader> headers)
    {
        for (HttpField f : this)
        {
            if (headers.contains(f.getHeader()))
                return true;
        }
        return false;
    }

    default boolean contains(String name)
    {
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    @Deprecated
    default boolean containsKey(String name)
    {
        return contains(name);
    }

    default String get(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return f.getValue();
        }
        return null;
    }

    default String get(String header)
    {
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(header))
                return f.getValue();
        }
        return null;
    }

    /**
     * Get multiple field values of the same name, split
     * as a {@link QuotedCSV}
     *
     * @param header The header
     * @param keepQuotes True if the fields are kept quoted
     * @return List the values with OWS stripped
     */
    default List<String> getCSV(HttpHeader header, boolean keepQuotes)
    {
        QuotedCSV values = null;
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
            {
                if (values == null)
                    values = new QuotedCSV(keepQuotes);
                values.addValue(f.getValue());
            }
        }
        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * Get multiple field values of the same name
     * as a {@link QuotedCSV}
     *
     * @param name the case-insensitive field name
     * @param keepQuotes True if the fields are kept quoted
     * @return List the values with OWS stripped
     */
    default List<String> getCSV(String name, boolean keepQuotes)
    {
        QuotedCSV values = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (values == null)
                    values = new QuotedCSV(keepQuotes);
                values.addValue(f.getValue());
            }
        }
        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * Get a header as a date value. Returns the value of a date field, or -1 if not found. The case
     * of the field name is ignored.
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a number of milliseconds since unix epoch
     */
    default long getDateField(String name)
    {
        HttpField field = getField(name);
        if (field == null)
            return -1;

        String val = HttpField.getValueParameters(field.getValue(), null);
        if (val == null)
            return -1;

        final long date = DateParser.parseDate(val);
        if (date == -1)
            throw new IllegalArgumentException("Cannot convert date: " + val);
        return date;
    }

    /**
     * Get a Field by index.
     *
     * @param index the field index
     * @return A Field value or null if the Field value has not been set
     */
    HttpField getField(int index);

    default HttpField getField(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return f;
        }
        return null;
    }

    default HttpField getField(String name)
    {
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
                return f;
        }
        return null;
    }

    /**
     * Get enumeration of header _names. Returns an enumeration of strings representing the header
     * _names for this request.
     *
     * @return an enumeration of field names
     */
    default Enumeration<String> getFieldNames()
    {
        return Collections.enumeration(getFieldNamesCollection());
    }

    /**
     * Get Set of header names.
     *
     * @return the unique set of field names.
     */
    default Set<String> getFieldNamesCollection()
    {
        return stream().map(HttpField::getName).collect(Collectors.toSet());
    }

    /**
     * Get multiple fields of the same header
     *
     * @param header the header
     * @return List the fields
     */
    default List<HttpField> getFields(HttpHeader header)
    {
        return stream().filter(f -> f.getHeader().equals(header)).collect(Collectors.toList());
    }

    /**
     * Get a header as an long value. Returns the value of an integer field or -1 if not found. The
     * case of the field name is ignored.
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a long
     * @throws NumberFormatException If bad long found
     */
    default long getLongField(String name) throws NumberFormatException
    {
        HttpField field = getField(name);
        return field == null ? -1L : field.getLongValue();
    }

    /**
     * Get a header as an long value. Returns the value of an integer field or -1 if not found. The
     * case of the field name is ignored.
     *
     * @param header the header type
     * @return the value of the field as a long
     * @throws NumberFormatException If bad long found
     */
    default long getLongField(HttpHeader header) throws NumberFormatException
    {
        HttpField field = getField(header);
        return field == null ? -1L : field.getLongValue();
    }

    /**
     * Get multiple field values of the same name, split and
     * sorted as a {@link QuotedQualityCSV}
     *
     * @param header The header
     * @return List the values in quality order with the q param and OWS stripped
     */
    default List<String> getQualityCSV(HttpHeader header)
    {
        return getQualityCSV(header, null);
    }

    /**
     * Get multiple field values of the same name, split and
     * sorted as a {@link QuotedQualityCSV}
     *
     * @param header The header
     * @param secondaryOrdering Function to apply an ordering other than specified by quality
     * @return List the values in quality order with the q param and OWS stripped
     */
    default List<String> getQualityCSV(HttpHeader header, ToIntFunction<String> secondaryOrdering)
    {
        QuotedQualityCSV values = null;
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
            {
                if (values == null)
                    values = new QuotedQualityCSV(secondaryOrdering);
                values.addValue(f.getValue());
            }
        }

        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * Get multiple field values of the same name, split and
     * sorted as a {@link QuotedQualityCSV}
     *
     * @param name the case-insensitive field name
     * @return List the values in quality order with the q param and OWS stripped
     */
    default List<String> getQualityCSV(String name)
    {
        QuotedQualityCSV values = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (values == null)
                    values = new QuotedQualityCSV();
                values.addValue(f.getValue());
            }
        }
        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * Get multi headers
     *
     * @param name the case-insensitive field name
     * @return Enumeration of the values
     */
    default Enumeration<String> getValues(String name)
    {
        Iterator<HttpField> i = iterator();
        return new Enumeration<>()
        {
            HttpField _field;

            @Override
            public boolean hasMoreElements()
            {
                if (_field != null)
                    return true;
                while (i.hasNext())
                {
                    HttpField f = i.next();
                    if (f.getName().equalsIgnoreCase(name) && f.getValue() != null)
                    {
                        _field = f;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String nextElement()
            {
                if (hasMoreElements())
                {
                    String value = _field.getValue();
                    _field = null;
                    return value;
                }
                throw new NoSuchElementException();
            }
        };
    }

    /**
     * Get multiple field values of the same name
     *
     * @param header the header
     * @return List the values
     */
    default List<String> getValuesList(HttpHeader header)
    {
        final List<String> list = new ArrayList<>();
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                list.add(f.getValue());
        }
        return list;
    }

    /**
     * Get multiple header of the same name
     *
     * @param name the case-insensitive field name
     * @return List the header values
     */
    default List<String> getValuesList(String name)
    {
        final List<String> list = new ArrayList<>();
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
                list.add(f.getValue());
        }
        return list;
    }

    default boolean isEqualTo(HttpFields that)
    {
        if (size() != that.size())
            return false;

        // Order is not important, so we cannot rely on List.equals(). // TODO is this true?
        loop:
        for (HttpField fi : this)
        {
            for (HttpField fa : that)
            {
                if (fi.equals(fa))
                    continue loop;
            }
            return false;
        }
        return true;
    }

    int size();

    Stream<HttpField> stream();
}
