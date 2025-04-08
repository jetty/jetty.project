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

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * <p>An ordered collection of {@link HttpField}s that represent the HTTP headers
 * or HTTP trailers of an HTTP request or an HTTP response.</p>
 *
 * <p>{@link HttpFields} is immutable and typically used in server-side HTTP requests
 * and client-side HTTP responses, while {@link HttpFields.Mutable} is mutable and
 * typically used in server-side HTTP responses and client-side HTTP requests.</p>
 *
 * <p>Access is always more efficient using {@link HttpHeader} keys rather than {@link String} field names.</p>
 *
 * <p>The primary implementations of {@code HttpFields} have been optimized assuming few
 * lookup operations, thus typically if many {@link HttpField}s need to looked up, it may be
 * better to use an {@link Iterator} to find multiple fields in a single iteration.</p>
 */
public interface HttpFields extends Iterable<HttpField>, Supplier<HttpFields>
{
    /**
     * <p>A constant {@link HttpField} for the HTTP header:</p>
     * <p>{@code Expires: Thu, 01 Jan 1970 00:00:00 GMT}</p>
     */
    HttpField EXPIRES_01JAN1970 = new PreEncodedHttpField(HttpHeader.EXPIRES, DateGenerator.__01Jan1970);
    /**
     * <p>A constant {@link HttpField} for the HTTP header:</p>
     * <p>{@code Connection: close}</p>
     */
    HttpField CONNECTION_CLOSE = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
    /**
     * <p>A constant {@link HttpField} for the HTTP header:</p>
     * <p>{@code Connection: keep-alive}</p>
     */
    HttpField CONNECTION_KEEPALIVE = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
    /**
     * <p>A constant {@link HttpField} for the HTTP header:</p>
     * <p>{@code Content-Length: 0}</p>
     */
    HttpField CONTENT_LENGTH_0 = new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH, 0L);

    /**
     * <p>A constant for an immutable and empty {@link HttpFields}.</p>
     */
    HttpFields EMPTY = new EmptyHttpFields();

    /**
     * <p>Returns an empty {@link Mutable} instance.</p>
     *
     * @return an empty {@link Mutable} instance
     */
    static Mutable build()
    {
        return new org.eclipse.jetty.http.MutableHttpFields();
    }

    /**
     * <p>Returns an empty {@link Mutable} instance with the given initial {@code capacity}.</p>
     * <p>The given {@code capacity} indicates the initial capacity of the storage for the
     * {@link HttpField}s.</p>
     * <p>When the capacity is exceeded, the storage is resized to accommodate the additional
     * {@link HttpField}s.</p>
     *
     * @param capacity the initial capacity of the storage for the {@link HttpField}s
     * @return an empty instance with the given initial {@code capacity}
     */
    static Mutable build(int capacity)
    {
        return new org.eclipse.jetty.http.MutableHttpFields(capacity);
    }

    /**
     * <p>Returns a new {@link Mutable} instance containing a copy of all the
     * {@link HttpField}s of the given {@link HttpFields} parameter.</p>
     *
     * @param fields the {@link HttpFields} to copy
     * @return a new {@link Mutable} instance containing a copy of the given {@link HttpFields}
     */
    static Mutable build(HttpFields fields)
    {
        return new org.eclipse.jetty.http.MutableHttpFields(fields);
    }

    /**
     * <p>Returns a new {@link Mutable} instance containing a copy of all the
     * {@link HttpField}s of the given {@link HttpFields}, replacing with the
     * given {@link HttpField} all the fields {@link HttpField}s with the same
     * name.</p>
     * <p>All existing {@link HttpField}s with the same name as the
     * replacing {@link HttpField} will be replaced by the given {@link HttpField}.
     * If there are no fields with that name, the given {@link HttpField}
     * is added to the returned {@link Mutable} instance.</p>
     *
     * @param fields the {@link HttpFields} to copy
     * @param replaceField the {@link HttpField} that replaces others with the same name
     * @return a new {@link Mutable} instance containing the given {@link HttpFields}
     * and the given replacement {@link HttpField}
     */
    static Mutable build(HttpFields fields, HttpField replaceField)
    {
        return new org.eclipse.jetty.http.MutableHttpFields(fields, replaceField);
    }

    /**
     * <p>Returns a new {@link Mutable} instance containing a copy of all the
     * {@link HttpField}s of the given {@link HttpFields}, removing the
     * {@link HttpField}s with the given names.</p>
     *
     * @param fields the {@link HttpFields} to copy
     * @param removeFields the names of the fields to remove
     * @return a new {@link Mutable} instance containing the given {@link HttpFields}
     * without the fields with the given names
     */
    static Mutable build(HttpFields fields, EnumSet<HttpHeader> removeFields)
    {
        return new org.eclipse.jetty.http.MutableHttpFields(fields, removeFields);
    }

    /**
     * <p>Returns an immutable {@link HttpFields} instance containing the given
     * {@link HttpField}s.</p>
     *
     * @param fields the {@link HttpField}s to be contained in the returned instance
     * @return a new immutable {@link HttpFields} instance with the given {@link HttpField}s
     */
    static HttpFields from(HttpField... fields)
    {
        return new org.eclipse.jetty.http.ImmutableHttpFields(fields);
    }

    /**
     * <p>Supplies this instance, typically used to supply HTTP trailers.</p>
     *
     * @return this instance
     */
    @Override
    default HttpFields get()
    {
        return this;
    }

    @Override
    default Iterator<HttpField> iterator()
    {
        return listIterator();
    }

    /**
     * @return an iterator over the {@link HttpField}s in this {@code HttpFields}.
     * @see #listIterator(int)
     */
    default ListIterator<HttpField> listIterator()
    {
        return listIterator(0);
    }

    /**
     * @return an iterator over the {@link HttpField}s in this {@code HttpFields} starting at the given index.
     * @see #listIterator()
     */
    ListIterator<HttpField> listIterator(int index);

    /**
     * <p>Returns an immutable copy of this {@link HttpFields} instance.</p>
     *
     * @return a new immutable copy of this {@link HttpFields} instance
     */
    default HttpFields asImmutable()
    {
        return HttpFields.build(this).asImmutable();
    }

    /**
     * <p>Returns the HTTP/1.1 string representation of the {@link HttpField}s of this instance.</p>
     * <p>The format of each field is: {@code <name>: <value>\r\n}, and there will be
     * an additional {@code \r\n} after the last field, for example:</p>
     * <pre>{@code
     * Host: localhost\r\n
     * Connection: close\r\n
     * \r\n
     * }</pre>
     *
     * @return an HTTP/1.1 string representation of the {@link HttpField}s of this instance
     */
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

    /**
     * <p>Returns whether this instance contains the given {@link HttpField}.</p>
     * <p>The comparison of {@link HttpField} names is case-insensitive via
     * {@link HttpField#isSameName(HttpField)}.
     * The comparison of the field value is case-insensitive via
     * {@link HttpField#contains(String)}.</p>
     *
     * @param field the {@link HttpField} to search for
     * @return whether this instance contains the given {@link HttpField}
     */
    default boolean contains(HttpField field)
    {
        for (HttpField f : this)
        {
            if (f.isSameName(field) && (f.equals(field) || f.contains(field.getValue())))
                return true;
        }
        return false;
    }

    /**
     * <p>Returns whether this instance contains the given {@link HttpHeader}
     * with the given value.</p>
     * <p>The comparison of the field value is case-insensitive via
     * {@link HttpField#contains(String)}.</p>
     *
     * @param header the name of the field to search for
     * @param value the value to search for
     * @return whether this instance contains the given
     * {@link HttpHeader} with the given value
     */
    default boolean contains(HttpHeader header, String value)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header && f.contains(value))
                return true;
        }
        return false;
    }

    /**
     * Look for a value as the last value in a possible multivalued field.
     * Parameters and specifically quality parameters are not considered.
     * @param header The {@link HttpHeader} type to search for.
     * @param value The value to search for (case-insensitive)
     * @return True iff the value is contained in the field value entirely or
     * as the last element of a quoted comma separated list.
     * @see HttpField#containsLast(String)
     */
    default boolean containsLast(HttpHeader header, String value)
    {
        for (ListIterator<HttpField> i = listIterator(size()); i.hasPrevious();)
        {
            HttpField f = i.previous();

            if (f.getHeader() == header)
                return f.containsLast(value);
        }
        return false;
    }

    /**
     * <p>Returns whether this instance contains the given field name
     * with the given value.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.
     * The comparison of the field value is case-insensitive via
     * {@link HttpField#contains(String)}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @param value the field value to search for
     * @return whether this instance contains the given
     * field name with the given value
     */
    default boolean contains(String name, String value)
    {
        for (HttpField f : this)
        {
            if (f.is(name) && f.contains(value))
                return true;
        }
        return false;
    }

    /**
     * <p>Returns whether this instance contains the given field name.</p>
     *
     * @param header the field name to search for
     * @return whether this instance contains the given field name
     */
    default boolean contains(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return true;
        }
        return false;
    }

    /**
     * <p>Returns whether this instance contains at least one of the given
     * field names.</p>
     *
     * @param headers the field names to search among
     * @return whether this instance contains at least one of the given field names
     */
    default boolean contains(EnumSet<HttpHeader> headers)
    {
        for (HttpField f : this)
        {
            if (headers.contains(f.getHeader()))
                return true;
        }
        return false;
    }

    /**
     * <p>Returns whether this instance contains the given field name.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}. If possible, it is more efficient to use
     * {@link #contains(HttpHeader)}.
     *
     * @param name the case-insensitive field name to search for
     * @return whether this instance contains the given field name
     * @see #contains(HttpHeader) 
     */
    default boolean contains(String name)
    {
        for (HttpField f : this)
        {
            if (f.is(name))
                return true;
        }
        return false;
    }

    /**
     * <p>Returns the encoded value of the first field with the given field name,
     * or {@code null} if no such header is present.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param header the field name to search for
     * @return the raw value of the first field with the given field name,
     * or {@code null} if no such header is present
     * @see HttpField#getValue()
     */
    default String get(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return f.getValue();
        }
        return null;
    }

    /**
     * <p>Returns the encoded value of the last field with the given field name,
     * or {@code null} if no such header is present.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param header the field name to search for
     * @return the raw value of the last field with the given field name,
     * or {@code null} if no such header is present
     * @see HttpField#getValue()
     */
    default String getLast(HttpHeader header)
    {
        for (ListIterator<HttpField> i = listIterator(size()); i.hasPrevious();)
        {
            HttpField f = i.previous();
            if (f.getHeader() == header)
                return f.getValue();
        }
        return null;
    }

    /**
     * <p>Returns the encoded value of the first field with the given field name,
     * or {@code null} if no such field is present.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}. If possible, it is more efficient to use {@link #get(HttpHeader)}.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return the raw value of the first field with the given field name,
     * or {@code null} if no such field is present
     * @see HttpField#getValue()
     * @see #get(HttpHeader) 
     */
    default String get(String name)
    {
        for (HttpField f : this)
        {
            if (f.is(name))
                return f.getValue();
        }
        return null;
    }

    /**
     * <p>Returns all the values of all the fields with the given field name.</p>
     * <p>In case of multi-valued fields, the multi-value of the field is split using
     * {@link QuotedCSV}, taking into account the given {@code keepQuotes} parameter.</p>
     *
     * @param header the field name to search for
     * @param keepQuotes whether the fields values should retain the quotes
     * @return a list of all the values of all the fields with the given name,
     * or an empty list if no such field name is present
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
     * <p>Returns all the values of all the fields with the given field name.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     * <p>In case of multi-valued fields, the multi-value of the field is split using
     * {@link QuotedCSV}, taking into account the given {@code keepQuotes} parameter.</p>
     *
     * @param name the case-insensitive field name to search for
     * @param keepQuotes whether the fields values should retain the quotes
     * @return a list of all the values of all the fields with the given name,
     * or an empty list if no such field name is present
     */
    default List<String> getCSV(String name, boolean keepQuotes)
    {
        QuotedCSV values = null;
        for (HttpField f : this)
        {
            if (f.is(name))
            {
                if (values == null)
                    values = new QuotedCSV(keepQuotes);
                values.addValue(f.getValue());
            }
        }
        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * <p>Returns the value of a date field as the number of milliseconds
     * since the Unix Epoch, or -1 if no such field is present.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return the value of the field as the number of milliseconds since Unix Epoch,
     * or -1 if no such field is present
     */
    default long getDateField(String name)
    {
        return parseDateField(getField(name));
    }

    /**
     * <p>Returns the value of a date field as the number of milliseconds
     * since the Unix Epoch, or -1 if no such field is present.</p>
     *
     * @param header the field name to search for
     * @return the value of the field as the number of milliseconds since Unix Epoch,
     * or -1 if no such field is present
     */
    default long getDateField(HttpHeader header)
    {
        return parseDateField(getField(header));
    }

    private static long parseDateField(HttpField field)
    {
        if (field == null)
            return -1;

        String val = HttpField.getValueParameters(field.getValue(), null);
        if (val == null)
            return -1;

        return TimeUnit.SECONDS.toMillis(HttpDateTime.parse(val).toEpochSecond());
    }

    /**
     * <p>Returns the {@link HttpField} at the given {@code index},
     * or {@code null} if there is no field at the given index.</p>
     *
     * @param index the index of the {@link HttpField}
     * @return the {@link HttpField} at the given {@code index},
     * or {@code null} if there is no field at the given index
     */
    default HttpField getField(int index)
    {
        int i = 0;
        for (HttpField f : this)
        {
            if (i++ == index)
                return f;
        }
        return null;
    }

    /**
     * <p>Returns the first {@link HttpField} with the given field name,
     * or {@code null} if no such field is present.</p>
     *
     * @param header the field name to search for
     * @return the first {@link HttpField} with the given field name,
     * or {@code null} if no such field is present
     */
    default HttpField getField(HttpHeader header)
    {
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
                return f;
        }
        return null;
    }

    /**
     * <p>Returns the first {@link HttpField} with the given field name,
     * or {@code null} if no such field is present.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return the first {@link HttpField} with the given field name,
     * or {@code null} if no such field is present
     */
    default HttpField getField(String name)
    {
        for (HttpField f : this)
        {
            if (f.is(name))
                return f;
        }
        return null;
    }

    /**
     * @return an enumeration of field names
     * @deprecated use {@link #getFieldNamesCollection()}
     */
    @Deprecated
    default Enumeration<String> getFieldNames()
    {
        return Collections.enumeration(getFieldNamesCollection());
    }

    /**
     * <p>Returns a {@link Set} of the field names.</p>
     * <p>Case-sensitivity of the field names is preserved.</p>
     *
     * @return an immutable {@link Set} of the field names. Changes made to the
     * {@code HttpFields} after this call are not reflected in the set.
     */
    default Set<String> getFieldNamesCollection()
    {
        Set<HttpHeader> seenByHeader = EnumSet.noneOf(HttpHeader.class);
        Set<String> buildByName = null;
        List<String> list = new ArrayList<>(size());

        for (HttpField f : this)
        {
            HttpHeader header = f.getHeader();
            if (header == null)
            {
                if (buildByName == null)
                    buildByName = new TreeSet<>(String::compareToIgnoreCase);
                if (buildByName.add(f.getName()))
                    list.add(f.getName());
            }
            else if (seenByHeader.add(header))
            {
                list.add(f.getName());
            }
        }

        Set<String> seenByName = buildByName;

        // use the list to retain a rough ordering
        return new AbstractSet<>()
        {
            @Override
            public Iterator<String> iterator()
            {
                return list.iterator();
            }

            @Override
            public int size()
            {
                return list.size();
            }

            @Override
            public boolean contains(Object o)
            {
                if (o instanceof String s)
                    return seenByName != null && seenByName.contains(s) || seenByHeader.contains(HttpHeader.CACHE.get(s));
                return false;
            }
        };
    }

    /**
     * <p>Returns all the {@link HttpField}s with the given field name.</p>
     *
     * @param header the field name to search for
     * @return a list of the {@link HttpField}s with the given field name,
     * or an empty list if no such field name is present
     */
    default List<HttpField> getFields(HttpHeader header)
    {
        return getFields(header, (f, h) -> f.getHeader() == h);
    }

    /**
     * <p>Returns all the {@link HttpField}s with the given field name.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return a list of the {@link HttpField}s with the given field name,
     * or an empty list if no such field name is present
     */
    default List<HttpField> getFields(String name)
    {
        return getFields(name, (f, n) -> f.is(name));
    }

    private <T> List<HttpField> getFields(T header, BiPredicate<HttpField, T> predicate)
    {
        return stream()
            .filter(f -> predicate.test(f, header))
            .collect(Collectors.toList());
    }

    /**
     * <p>Returns the value of a field as a {@code long} value,
     * or -1 if no such field is present.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a {@code long},
     * or -1 if no such field is present
     * @throws NumberFormatException if the value of the field
     * cannot be converted to a {@code long}
     */
    default long getLongField(String name) throws NumberFormatException
    {
        HttpField field = getField(name);
        return field == null ? -1L : field.getLongValue();
    }

    /**
     * <p>Returns the value of a field as a {@code long} value,
     * or -1 if no such field is present.</p>
     *
     * @param header the field name
     * @return the value of the field as a {@code long},
     * or -1 if no such field is present
     * @throws NumberFormatException if the value of the field
     * cannot be converted to a {@code long}
     */
    default long getLongField(HttpHeader header) throws NumberFormatException
    {
        HttpField field = getField(header);
        return field == null ? -1L : field.getLongValue();
    }

    /**
     * <p>Returns all the values of all the fields with the given name,
     * split and sorted in quality order using {@link QuotedQualityCSV}.</p>
     *
     * @param header the field name to search for
     * @return a list of all the values of all the fields with the given name,
     * split and sorted in quality order,
     * or an empty list if no such field name is present
     */
    default List<String> getQualityCSV(HttpHeader header)
    {
        return getQualityCSV(header, null);
    }

    /**
     * <p>Returns all the values of all the fields with the given name,
     * split and sorted first in quality order and then optionally further
     * sorted with the given function, using {@link QuotedQualityCSV}.</p>
     *
     * @param header the field name to search for
     * @param secondaryOrdering the secondary sort function, or {@code null}
     * for no secondary sort
     * @return a list of all the values of all the fields with the given name,
     * split and sorted in quality order and optionally further sorted with
     * the given function,
     * or an empty list if no such field name is present
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
     * <p>Returns all the values of all the fields with the given name,
     * split and sorted in quality order using {@link QuotedQualityCSV}.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return a list of all the values of all the fields with the given name,
     * split and sorted in quality order,
     * or an empty list if no such field name is present
     */
    default List<String> getQualityCSV(String name)
    {
        QuotedQualityCSV values = null;
        for (HttpField f : this)
        {
            if (f.is(name))
            {
                if (values == null)
                    values = new QuotedQualityCSV();
                values.addValue(f.getValue());
            }
        }
        return values == null ? Collections.emptyList() : values.getValues();
    }

    /**
     * <p>Returns an {@link Enumeration} of the encoded values of all the fields
     * with the given name.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param name the case-insensitive field name to search for
     * @return an {@link Enumeration} of the encoded values of all
     * the fields with the given name
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
                    if (f.is(name) && f.getValue() != null)
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
     * <p>Returns a list of the encoded values of all the fields
     * with the given name.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param header the field name to search for
     * @return a list of the encoded values of all the fields
     * with the given name,
     * or an empty list if no such field name is present
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
     * <p>Returns a list of the encoded values of all the fields
     * with the given name.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link HttpField#is(String)}.</p>
     * <p>In case of multi-valued fields, the returned value is the encoded
     * value, including commas and quotes, as returned by {@link HttpField#getValue()}.</p>
     *
     * @param name the field name to search for
     * @return a list of the encoded values of all the fields
     * with the given name,
     * or an empty list if no such field name is present
     */
    default List<String> getValuesList(String name)
    {
        final List<String> list = new ArrayList<>();
        for (HttpField f : this)
        {
            if (f.is(name))
                list.add(f.getValue());
        }
        return list;
    }

    /**
     * <p>Returns whether this instance is equal to the given instance.</p>
     * <p>Returns {@code true} if and only if the two instances have the
     * same number of fields, in the same order, and each field is equal,
     * but makes no difference between {@link Mutable} and non-{@link Mutable}
     * instances.</p>
     *
     * @param that the {@link HttpFields} instance to compare to
     * @return whether the two instances are equal
     */
    default boolean isEqualTo(HttpFields that)
    {
        if (size() != that.size())
            return false;

        Iterator<HttpField> i = that.iterator();
        for (HttpField f : this)
        {
            if (!i.hasNext())
                return false;
            if (!f.equals(i.next()))
                return false;
        }
        return !i.hasNext();
    }

    /**
     * @return the number of {@link HttpField}s in this instance
     */
    default int size()
    {
        int size = 0;
        for (HttpField ignored : this)
        {
            size++;
        }
        return size;
    }

    /**
     * <p>Wraps an instance of {@link HttpFields} as a {@link Map}.</p>
     * <p>If the provided {@link HttpFields} is an instance of {@link HttpFields.Mutable} then changes to the
     * {@link Map} will be reflected in the underlying {@link HttpFields}.
     * Otherwise, any modification to the {@link Map} will throw {@link UnsupportedOperationException}.</p>
     * @param fields the {@link HttpFields} to convert to a {@link Map}.
     * @return an {@link Map} representing the contents of the {@link HttpFields}.
     */
    static Map<String, List<String>> asMap(HttpFields fields)
    {
        return (fields instanceof HttpFields.Mutable mutable)
            ? new HttpFieldsMap.Mutable(mutable)
            : new HttpFieldsMap.Immutable(fields);
    }

    /**
     * @return a sequential stream of the {@link HttpField}s in this instance
     */
    default Stream<HttpField> stream()
    {
        return StreamSupport.stream(spliterator(), false);
    }

    /**
     * <p>A mutable version of {@link HttpFields}.</p>
     * <p>Name and value pairs representing HTTP headers or HTTP
     * trailers can be added to or removed from this instance.</p>
     */
    interface Mutable extends HttpFields
    {
        /**
         * <p>Adds a new {@link HttpField} with the given name and string value.</p>
         * <p>The new {@link HttpField} is added even if a field with the
         * same name is already present.</p>
         * <p>This method has no effect if null is passed for either the name or value parameters.</p>
         *
         * @param name the name of the field
         * @param value the value of the field
         * @return this instance
         */
        default Mutable add(String name, String value)
        {
            Objects.requireNonNull(name);
            if (value == null)
                return this;
            return add(new HttpField(name, value));
        }

        /**
         * <p>Adds a new {@link HttpField} with the given name and {@code long} value.</p>
         * <p>The new {@link HttpField} is added even if a field with the
         * same name is already present.</p>
         *
         * @param name the non-{@code null} name of the field
         * @param value the value of the field
         * @return this instance
         */
        default Mutable add(String name, long value)
        {
            Objects.requireNonNull(name);
            return add(new HttpField.LongValueHttpField(name, value));
        }

        /**
         * <p>Adds a new {@link HttpField} with the given name and value.</p>
         * <p>The new {@link HttpField} is added even if a field with the
         * same name is already present.</p>
         *
         * @param header the non-{@code null} name of the field
         * @param value the non-{@code null} value of the field
         * @return this instance
         */
        default Mutable add(HttpHeader header, HttpHeaderValue value)
        {
            Objects.requireNonNull(header);
            if (value == null)
                return this;
            return add(header, value.toString());
        }

        /**
         * <p>Adds a new {@link HttpField} with the given name and string value.</p>
         * <p>The new {@link HttpField} is added even if a field with the
         * same name is already present.</p>
         *
         * @param header the non-{@code null} name of the field
         * @param value the non-{@code null} value of the field
         * @return this instance
         */
        default Mutable add(HttpHeader header, String value)
        {
            Objects.requireNonNull(header);
            if (value == null)
                return this;
            return add(new HttpField(header, value));
        }

        /**
         * <p>Adds a new {@link HttpField} with the given name and {@code long} value.</p>
         * <p>The new {@link HttpField} is added even if a field with the
         * same name is already present.</p>
         *
         * @param header the non-{@code null} name of the field
         * @param value the value of the field
         * @return this instance
         */
        default Mutable add(HttpHeader header, long value)
        {
            Objects.requireNonNull(header);
            return add(new HttpField.LongValueHttpField(header, value));
        }

        /**
         * <p>Adds the given {@link HttpField} to this instance.</p>
         *
         * @param field the {@link HttpField} to add
         * @return this instance
         */
        default Mutable add(HttpField field)
        {
            Objects.requireNonNull(field);
            ListIterator<HttpField> i = listIterator(size());
            i.add(field);
            return this;
        }

        /**
         * <p>Adds all the {@link HttpField}s of the given {@link HttpFields}
         * to this instance.</p>
         *
         * @param fields the fields to add
         * @return this instance
         */
        default Mutable add(HttpFields fields)
        {
            Objects.requireNonNull(fields);
            for (HttpField field : fields)
            {
                add(field);
            }
            return this;
        }

        /**
         * <p>Adds a field associated with a list of values.</p>
         *
         * @param name the name of the field
         * @param list the List value of the field.
         * @return this builder
         */
        default Mutable add(String name, List<String> list)
        {
            Objects.requireNonNull(name);
            if (list == null || list.isEmpty())
                return this;
            if (list.size() == 1)
            {
                String v = list.get(0);
                return add(name, v == null ? "" : v);
            }
            HttpField field = new HttpField.MultiHttpField(name, list);
            return add(field);
        }

        /**
         * <p>Adds the given value(s) to the {@link HttpField} with the given name,
         * encoding them as comma-separated if necessary,
         * unless they are already present in existing fields with the same name.</p>
         *
         * @param header the field name of the field to add the value(s) to
         * @param values the value(s) to add
         * @return this instance
         */
        default Mutable addCSV(HttpHeader header, String... values)
        {
            Objects.requireNonNull(header);
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
            String value = formatCsvExcludingExisting(existing, values);
            if (value != null)
                add(header, value);
            return this;
        }

        /**
         * <p>Adds the given value(s) to the {@link HttpField} with the given name,
         * encoding them as comma-separated if necessary,
         * unless they are already present in existing fields with the same name.</p>
         *
         * @param name the field name of the field to add the value(s) to
         * @param values the value(s) to add
         * @return this instance
         */
        default Mutable addCSV(String name, String... values)
        {
            Objects.requireNonNull(name);
            QuotedCSV existing = null;
            for (HttpField f : this)
            {
                if (f.is(name))
                {
                    if (existing == null)
                        existing = new QuotedCSV(false);
                    existing.addValue(f.getValue());
                }
            }
            String value = formatCsvExcludingExisting(existing, values);
            if (value != null)
                add(name, value);
            return this;
        }

        /**
         * <p>Adds a new date {@link HttpField} with the given name and {@code date} value.</p>
         * The {@code date} parameter is the number of milliseconds from the Unix Epoch,
         * and it is formatted into a string via {@link DateGenerator#formatDate(long)}.
         *
         * @param name the non-{@code null} name of the field
         * @param date the field date value
         * @return this instance
         */
        default Mutable addDateField(String name, long date)
        {
            Objects.requireNonNull(name);
            add(name, DateGenerator.formatDate(date));
            return this;
        }

        /**
         * <p>Removes all the fields from this instance.</p>
         *
         * @return this instance
         */
        default Mutable clear()
        {
            Iterator<HttpField> i = iterator();
            while (i.hasNext())
            {
                i.next();
                i.remove();
            }
            return this;
        }

        /**
         * <p>Ensures that the given {@link HttpField} is present when the field
         * may not exist or may exist and be multi-valued.</p>
         * <p>Multiple existing {@link HttpField}s are merged into a single field.</p>
         *
         * @param field the field to ensure to be present
         */
        default void ensureField(HttpField field)
        {
            Objects.requireNonNull(field);
            HttpHeader header = field.getHeader();
            // Is the field value multi valued?
            if (field.getValue().indexOf(',') < 0)
            {
                if (header != null)
                    computeField(header, (h, l) -> computeEnsure(field, l));
                else
                    computeField(field.getName(), (h, l) -> computeEnsure(field, l));
            }
            else
            {
                if (header != null)
                    computeField(header, (h, l) -> computeEnsure(field, field.getValues(), l));
                else
                    computeField(field.getName(), (h, l) -> computeEnsure(field, field.getValues(), l));
            }
        }

        /**
         * <p>Puts the given {@link HttpField} into this instance.</p>
         * <p>If a fields with the same name is present, the given field
         * replaces it, and other existing fields with the same name
         * are removed, so that only the given field will be present.</p>
         * <p>If a field with the same name is not present, the given
         * field will be added.</p>
         *
         * @param field the field to put
         * @return this instance
         */
        default Mutable put(HttpField field)
        {
            Objects.requireNonNull(field);
            boolean put = false;
            ListIterator<HttpField> i = listIterator();
            while (i.hasNext())
            {
                HttpField f = i.next();
                if (f.isSameName(field))
                {
                    if (put)
                    {
                        i.remove();
                    }
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
         * <p>This method behaves like {@link #remove(String)} when
         * the given {@code value} is {@code null}, otherwise behaves
         * like {@link #put(HttpField)}.</p>
         *
         * @param name the name of the field
         * @param value the value of the field; if {@code null} the field is removed
         * @return this instance
         */
        default Mutable put(String name, String value)
        {
            Objects.requireNonNull(name);
            if (value == null)
                return remove(name);
            return put(new HttpField(name, value));
        }

        /**
         * <p>This method behaves like {@link #remove(HttpHeader)} when
         * the given {@code value} is {@code null}, otherwise behaves
         * like {@link #put(HttpField)}.</p>
         *
         * @param header the name of the field
         * @param value the value of the field; if {@code null} the field is removed
         * @return this instance
         */
        default Mutable put(HttpHeader header, HttpHeaderValue value)
        {
            Objects.requireNonNull(header);
            if (value == null)
                return remove(header);
            return put(new HttpField(header, value.toString()));
        }

        /**
         * <p>This method behaves like {@link #remove(HttpHeader)} when
         * the given {@code value} is {@code null}, otherwise behaves
         * like {@link #put(HttpField)}.</p>
         *
         * @param header the name of the field
         * @param value the value of the field; if {@code null} the field is removed
         * @return this instance
         */
        default Mutable put(HttpHeader header, String value)
        {
            Objects.requireNonNull(header);
            if (value == null)
                return remove(header);
            return put(new HttpField(header, value));
        }

        /**
         * <p>Puts a field associated with a list of values.</p>
         *
         * @param name the name of the field
         * @param list the List value of the field. If null the field is cleared.
         * @return this builder
         */
        default Mutable put(String name, List<String> list)
        {
            Objects.requireNonNull(name);
            if (list == null || list.isEmpty())
                return remove(name);
            if (list.size() == 1)
            {
                String value = list.get(0);
                return put(name, value == null ? "" : value);
            }

            HttpField field = new HttpField.MultiHttpField(name, list);
            return put(field);
        }

        /**
         * <p>Puts a new date {@link HttpField} with the given name and {@code date} value,
         * with the semantic of {@link #put(HttpField)}.</p>
         * The {@code date} parameter is the number of milliseconds from the Unix Epoch,
         * and it is formatted into a string via {@link DateGenerator#formatDate(long)}.
         *
         * @param name the non-{@code null} name of the field
         * @param date the field date value
         * @return this instance
         */
        default Mutable putDate(HttpHeader name, long date)
        {
            Objects.requireNonNull(name);
            return put(name, DateGenerator.formatDate(date));
        }

        /**
         * <p>Puts a new date {@link HttpField} with the given name and {@code date} value,
         * with the semantic of {@link #put(HttpField)}.</p>
         * The {@code date} parameter is the number of milliseconds from the Unix Epoch,
         * and it is formatted into a string via {@link DateGenerator#formatDate(long)}.
         *
         * @param name the non-{@code null} name of the field
         * @param date the field date value
         * @return this instance
         */
        default Mutable putDate(String name, long date)
        {
            Objects.requireNonNull(name);
            return put(name, DateGenerator.formatDate(date));
        }

        /**
         * <p>Puts a new {@link HttpField} with the given name and {@code long} value,
         * with the semantic of {@link #put(HttpField)}.</p>
         *
         * @param header the non-{@code null} name of the field
         * @param value the value of the field
         * @return this instance
         */
        default Mutable put(HttpHeader header, long value)
        {
            Objects.requireNonNull(header);
            if (value == 0 && header == HttpHeader.CONTENT_LENGTH)
                return put(HttpFields.CONTENT_LENGTH_0);
            return put(new HttpField.LongValueHttpField(header, value));
        }

        /**
         * <p>Puts a new {@link HttpField} with the given name and {@code long} value,
         * with the semantic of {@link #put(HttpField)}.</p>
         *
         * @param name the non-{@code null} name of the field
         * @param value the value of the field
         * @return this instance
         */
        default Mutable put(String name, long value)
        {
            Objects.requireNonNull(name);
            if (value == 0 && HttpHeader.CONTENT_LENGTH.is(name))
                return put(HttpFields.CONTENT_LENGTH_0);
            return put(new HttpField.LongValueHttpField(name, value));
        }

        /**
         * <p>Computes a single field for the given {@link HttpHeader} and for existing fields with the same header.</p>
         *
         * <p>The compute function receives the field name and a list of fields with the same name
         * so that their values can be used to compute the value of the field that is returned
         * by the compute function parameter.
         * If the compute function returns {@code null}, the fields with the given name are removed.</p>
         * <p>This method comes handy when you want to add an HTTP header if it does not exist,
         * or add a value if the HTTP header already exists, similarly to
         * {@link Map#compute(Object, BiFunction)}.</p>
         *
         * <p>This method can be used to {@link #put(HttpField) put} a new field (or blindly replace its value):</p>
         * <pre>{@code
         * httpFields.computeField("X-New-Header",
         *     (name, fields) -> new HttpField(name, "NewValue"));
         * }</pre>
         *
         * <p>This method can be used to coalesce many fields into one:</p>
         * <pre>{@code
         * // Input:
         * GET / HTTP/1.1
         * Host: localhost
         * Cookie: foo=1
         * Cookie: bar=2,baz=3
         * User-Agent: Jetty
         *
         * // Computation:
         * httpFields.computeField("Cookie", (name, fields) ->
         * {
         *     // No cookies, nothing to do.
         *     if (fields == null)
         *         return null;
         *
         *     // Coalesces all cookies.
         *     String coalesced = fields.stream()
         *         .flatMap(field -> Stream.of(field.getValues()))
         *         .collect(Collectors.joining(", "));
         *
         *     // Returns a single Cookie header with all cookies.
         *     return new HttpField(name, coalesced);
         * }
         *
         * // Output:
         * GET / HTTP/1.1
         * Host: localhost
         * Cookie: foo=1, bar=2, baz=3
         * User-Agent: Jetty
         * }</pre>
         *
         * <p>This method can be used to replace a field:</p>
         * <pre>{@code
         * httpFields.computeField("X-Length", (name, fields) ->
         * {
         *     if (fields == null)
         *         return null;
         *
         *     // Get any value among the X-Length headers.
         *     String length = fields.stream()
         *         .map(HttpField::getValue)
         *         .findAny()
         *         .orElse("0");
         *
         *     // Replace X-Length headers with X-Capacity header.
         *     return new HttpField("X-Capacity", length);
         * });
         * }</pre>
         *
         * <p>This method can be used to remove a field:</p>
         * <pre>{@code
         * httpFields.computeField("Connection", (name, fields) -> null);
         * }</pre>
         *
         * @param header the field name
         * @param computeFn the compute function
         * @return this instance
         */
        default Mutable computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
        {
            Objects.requireNonNull(header);
            HttpField result = computeFn.apply(header, stream().filter(f -> f.getHeader() == header).toList());
            return result != null ? put(result) : remove(header);
        }

        /**
         * <p>Computes a single field for the given HTTP field name and for existing fields with the same name.</p>
         *
         * @param name the field name
         * @param computeFn the compute function
         * @return this instance
         * @see #computeField(HttpHeader, BiFunction)
         */
        default Mutable computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
        {
            Objects.requireNonNull(name);
            HttpField result = computeFn.apply(name, stream().filter(f -> f.is(name)).toList());
            return result != null ? put(result) : remove(name);
        }

        /**
         * <p>Removes all the fields with the given name.</p>
         *
         * @param header the name of the fields to remove
         * @return this instance
         */
        default Mutable remove(HttpHeader header)
        {
            Objects.requireNonNull(header);
            Iterator<HttpField> i = iterator();
            while (i.hasNext())
            {
                HttpField f = i.next();
                if (f.getHeader() == header)
                    i.remove();
            }
            return this;
        }

        /**
         * <p>Removes all the fields with the given names.</p>
         *
         * @param headers the names of the fields to remove
         * @return this instance
         */
        default Mutable remove(EnumSet<HttpHeader> headers)
        {
            Iterator<HttpField> i = iterator();
            while (i.hasNext())
            {
                HttpField f = i.next();
                HttpHeader h = f.getHeader();
                if (h != null && headers.contains(h))
                    i.remove();
            }
            return this;
        }

        /**
         * <p>Removes all the fields with the given name.</p>
         *
         * @param name the name of the fields to remove
         * @return this instance
         */
        default Mutable remove(String name)
        {
            Objects.requireNonNull(name);
            for (ListIterator<HttpField> i = listIterator(); i.hasNext(); )
            {
                HttpField f = i.next();
                if (f.is(name))
                    i.remove();
            }
            return this;
        }

        private static String formatCsvExcludingExisting(QuotedCSV existing, String... values)
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
                    if (!value.isEmpty())
                        value.append(", ");
                    value.append(v);
                }
                if (!value.isEmpty())
                    return value.toString();
            }

            return null;
        }

        private static HttpField computeEnsure(HttpField ensure, List<HttpField> fields)
        {
            // If no existing fields return the ensure field
            if (fields == null || fields.isEmpty())
                return ensure;

            String ensureValue = ensure.getValue();

            // Handle a single existing field
            if (fields.size() == 1)
            {
                // If the existing field contains the ensure value, return it, else append values.
                HttpField f = fields.get(0);
                return f.contains(ensureValue)
                    ? f
                    : new HttpField(ensure.getHeader(), ensure.getName(), f.getValue() + ", " + ensureValue);
            }

            // Handle multiple existing fields
            StringBuilder v = new StringBuilder();
            for (HttpField f : fields)
            {
                // Always append multiple fields into a single field value
                if (!v.isEmpty())
                    v.append(", ");
                v.append(f.getValue());

                // check if the ensure value is already contained
                if (ensureValue != null && f.contains(ensureValue))
                    ensureValue = null;
            }

            // If the ensure value was not contained append it
            if (ensureValue != null)
                v.append(", ").append(ensureValue);

            return new HttpField(ensure.getHeader(), ensure.getName(), v.toString());
        }

        private static HttpField computeEnsure(HttpField ensure, String[] values, List<HttpField> fields)
        {
            // If no existing fields return the ensure field
            if (fields == null || fields.isEmpty())
                return ensure;

            // Handle a single existing field
            if (fields.size() == 1)
            {
                HttpField f = fields.get(0);
                // check which ensured values are already contained
                int ensured = values.length;
                for (int i = 0; i < values.length; i++)
                {
                    if (f.contains(values[i]))
                    {
                        ensured--;
                        values[i] = null;
                    }
                }

                // if all ensured values contained return the existing field
                if (ensured == 0)
                    return f;
                // else if no ensured values contained append the entire ensured valued
                if (ensured == values.length)
                    return new HttpField(ensure.getHeader(), ensure.getName(),
                        f.getValue() + ", " + ensure.getValue());
                // else append just the ensured values that are not contained
                StringBuilder v = new StringBuilder(f.getValue());
                for (String value : values)
                {
                    if (value != null)
                        v.append(", ").append(value);
                }
                return new HttpField(ensure.getHeader(), ensure.getName(), v.toString());
            }

            // Handle a multiple existing field
            StringBuilder v = new StringBuilder();
            int ensured = values.length;
            for (HttpField f : fields)
            {
                // Always append multiple fields into a single field value
                if (!v.isEmpty())
                    v.append(", ");
                v.append(f.getValue());

                // null out ensured values that are included
                for (int i = 0; i < values.length; i++)
                {
                    if (values[i] != null && f.contains(values[i]))
                    {
                        ensured--;
                        values[i] = null;
                    }
                }
            }

            // if no ensured values exist append them all
            if (ensured == values.length)
                v.append(", ").append(ensure.getValue());
                // else if some ensured values are missing, append them
            else if (ensured > 0)
            {
                for (String value : values)
                {
                    if (value != null)
                        v.append(", ").append(value);
                }
            }

            // return a merged header with missing ensured values added
            return new HttpField(ensure.getHeader(), ensure.getName(), v.toString());
        }

        /**
         * A wrapper of {@link HttpFields} instances.
         */
        class Wrapper implements Mutable
        {
            private final Mutable _fields;

            public Wrapper(Mutable fields)
            {
                _fields = fields;
            }

            public Mutable getWrapped()
            {
                return _fields;
            }

            /**
             * Called when a field is added (including as part of a put).
             *
             * @param field The field being added.
             * @return The field to add, or null if the add is to be ignored.
             */
            public HttpField onAddField(HttpField field)
            {
                return field;
            }

            /**
             * Called when a field is removed (including as part of a put).
             *
             * @param field The field being removed.
             * @return True if the field should be removed, false otherwise.
             */
            public boolean onRemoveField(HttpField field)
            {
                return true;
            }

            public HttpField onReplaceField(HttpField oldField, HttpField newField)
            {
                return newField;
            }

            @Override
            public int size()
            {
                // This impl needed only as an optimization
                return _fields.size();
            }

            @Override
            public Stream<HttpField> stream()
            {
                // This impl needed only as an optimization
                return _fields.stream();
            }

            @Override
            public Mutable add(HttpField field)
            {
                if (field != null)
                {
                    field = onAddField(field);
                    if (field != null)
                        _fields.add(field);
                }
                return this;
            }

            @Override
            public Mutable put(HttpField field)
            {
                Objects.requireNonNull(field);
                // rewrite put to ensure that removes are called before replace
                int put = -1;
                ListIterator<HttpField> i = _fields.listIterator();
                while (i.hasNext())
                {
                    HttpField f = i.next();
                    if (f.isSameName(field))
                    {
                        if (put < 0)
                            put = i.previousIndex();
                        else if (onRemoveField(f))
                            i.remove();
                    }
                }

                if (put < 0)
                {
                    field = onAddField(field);
                    if (field != null)
                        _fields.add(field);
                }
                else
                {
                    i = _fields.listIterator(put);
                    HttpField old = i.next();
                    field = onReplaceField(old, field);
                    if (field == null)
                        i.remove();
                    else if (field != old)
                        i.set(field);
                }

                return this;
            }

            @Override
            public Mutable clear()
            {
                _fields.clear();
                return this;
            }

            @Override
            public ListIterator<HttpField> listIterator(int index)
            {
                ListIterator<HttpField> i = _fields.listIterator(index);
                return new ListIterator<>()
                {
                    HttpField last;

                    @Override
                    public boolean hasNext()
                    {
                        return i.hasNext();
                    }

                    @Override
                    public HttpField next()
                    {
                        return last = i.next();
                    }

                    @Override
                    public boolean hasPrevious()
                    {
                        return i.hasPrevious();
                    }

                    @Override
                    public HttpField previous()
                    {
                        return last = i.previous();
                    }

                    @Override
                    public int nextIndex()
                    {
                        return i.nextIndex();
                    }

                    @Override
                    public int previousIndex()
                    {
                        return i.previousIndex();
                    }

                    @Override
                    public void remove()
                    {
                        if (last != null && onRemoveField(last))
                        {
                            last = null;
                            i.remove();
                        }
                    }

                    @Override
                    public void set(HttpField field)
                    {
                        if (field == null)
                        {
                            if (last != null && onRemoveField(last))
                            {
                                last = null;
                                i.remove();
                            }
                        }
                        else
                        {
                            if (last != null)
                            {
                                field = onReplaceField(last, field);
                                if (field == null)
                                {
                                    last = null;
                                    i.remove();
                                }
                                else if (field != last)
                                {
                                    last = null;
                                    i.set(field);
                                }
                            }
                        }
                    }

                    @Override
                    public void add(HttpField field)
                    {
                        if (field != null)
                        {
                            field = onAddField(field);
                            if (field != null)
                            {
                                last = null;
                                i.add(field);
                            }
                        }
                    }
                };
            }
        }
    }

    @Deprecated(forRemoval = true)
    class MutableHttpFields extends org.eclipse.jetty.http.MutableHttpFields
    {
    }

    @Deprecated(forRemoval = true)
    class ImmutableHttpFields extends org.eclipse.jetty.http.ImmutableHttpFields
    {
        protected ImmutableHttpFields(HttpField[] fields, int size)
        {
            super(fields, size);
        }
    }
}
