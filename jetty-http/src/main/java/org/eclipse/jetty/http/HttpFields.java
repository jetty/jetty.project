//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface that represents on ordered collection of {@link HttpField}s.
 * Both {@link Mutable} and {@link Immutable} implementations are available
 * via the static methods such as {@link #build()} and {@link #from(HttpField...)}.
 */
public interface HttpFields extends Iterable<HttpField>
{
    HttpFields EMPTY = build().asImmutable();

    static Mutable build()
    {
        return new Mutable();
    }

    static Mutable build(int capacity)
    {
        return new Mutable(capacity);
    }

    static Mutable build(HttpFields fields)
    {
        return new Mutable(fields);
    }

    static Mutable build(HttpFields fields, HttpField replaceField)
    {
        return new Mutable(fields, replaceField);
    }

    static Mutable build(HttpFields fields, EnumSet<HttpHeader> removeFields)
    {
        return new Mutable(fields, removeFields);
    }

    static Immutable from(HttpField... fields)
    {
        return new Immutable(fields);
    }

    static Immutable from(HttpFields fields, Function<HttpField, HttpField> mutation)
    {
        return new Immutable(fields.stream().map(mutation).filter(Objects::nonNull).toArray(HttpField[]::new));
    }

    HttpFields asImmutable();

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
            if (f.is(name) && f.contains(value))
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
            if (f.is(name))
                return true;
        }
        return false;
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
            if (f.is(header))
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
     * Get a header as a date value. Returns the value of a date field, or -1 if not found. The case
     * of the field name is ignored.
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a number of milliseconds since unix epoch
     */
    default long getDateField(String name)
    {
        return parseDateField(getField(name));
    }

    /**
     * Get a header as a date value. Returns the value of a date field, or -1 if not found. The case
     * of the field name is ignored.
     *
     * @param header the header
     * @return the value of the field as a number of milliseconds since unix epoch
     */
    default long getDateField(HttpHeader header)
    {
        return parseDateField(getField(header));
    }

    static long parseDateField(HttpField field)
    {
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
            if (f.is(name))
                return f;
        }
        return null;
    }

    /**
     * Get enumeration of header _names. Returns an enumeration of strings representing the header
     * _names for this request.
     *
     * @return an enumeration of field names
     * @deprecated use {@link #getFieldNamesCollection()}
     */
    @Deprecated
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
        return getFields(header, (f, h) -> f.getHeader() == h);
    }

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
            if (f.is(name))
                list.add(f.getValue());
        }
        return list;
    }

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

    int size();

    Stream<HttpField> stream();

    /**
     * HTTP Fields. A collection of HTTP header and or Trailer fields.
     *
     * <p>This class is not synchronized as it is expected that modifications will only be performed by a
     * single thread.
     *
     * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
     */
    class Mutable implements Iterable<HttpField>, HttpFields
    {
        private HttpField[] _fields;
        private int _size;

        /**
         * Initialize an empty HttpFields.
         */
        protected Mutable()
        {
            this(16);  // Based on small sample of Chrome requests.
        }

        /**
         * Initialize an empty HttpFields.
         *
         * @param capacity the capacity of the http fields
         */
        Mutable(int capacity)
        {
            _fields = new HttpField[capacity];
        }

        /**
         * Initialize HttpFields from another.
         *
         * @param fields the fields to copy data from
         */
        Mutable(HttpFields fields)
        {
            add(fields);
        }

        /**
         * Initialize HttpFields from another and replace a field
         *
         * @param fields the fields to copy data from
         * @param replaceField the replacement field
         */
        Mutable(HttpFields fields, HttpField replaceField)
        {
            _fields = new HttpField[fields.size() + 4];
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
        Mutable(HttpFields fields, EnumSet<HttpHeader> removeFields)
        {
            _fields = new HttpField[fields.size() + 4];
            _size = 0;
            for (HttpField f : fields)
            {
                if (f.getHeader() == null || !removeFields.contains(f.getHeader()))
                    _fields[_size++] = f;
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
        public Mutable add(String name, String value)
        {
            if (value != null)
                return add(new HttpField(name, value));
            return this;
        }

        public Mutable add(HttpHeader header, HttpHeaderValue value)
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
        public Mutable add(HttpHeader header, String value)
        {
            if (value == null)
                throw new IllegalArgumentException("null value");

            HttpField field = new HttpField(header, value);
            return add(field);
        }

        public Mutable add(HttpField field)
        {
            if (field != null)
            {
                if (_size == _fields.length)
                    _fields = Arrays.copyOf(_fields, _size * 2);
                _fields[_size++] = field;
            }
            return this;
        }

        public Mutable add(HttpFields fields)
        {
            if (_fields == null)
                _fields = new HttpField[fields.size() + 4];
            else if (_size + fields.size() >= _fields.length)
                _fields = Arrays.copyOf(_fields, _size + fields.size() + 4);

            if (fields.size() == 0)
                return this;

            if (fields instanceof Immutable)
            {
                Immutable b = (Immutable)fields;
                System.arraycopy(b._fields, 0, _fields, _size, b._fields.length);
                _size += b._fields.length;
            }
            else if (fields instanceof Mutable)
            {
                Mutable b = (Mutable)fields;
                System.arraycopy(b._fields, 0, _fields, _size, b._size);
                _size += b._size;
            }
            else
            {
                for (HttpField f : fields)
                    _fields[_size++] = f;
            }
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
        public Mutable addCSV(HttpHeader header, String... values)
        {
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
         * Add comma separated values, but only if not already
         * present.
         *
         * @param name The header to add the value(s) to
         * @param values The value(s) to add
         * @return this builder
         */
        public Mutable addCSV(String name, String... values)
        {
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
         * Sets the value of a date field.
         *
         * @param name the field name
         * @param date the field date value
         * @return this builder
         */
        public Mutable addDateField(String name, long date)
        {
            add(name, DateGenerator.formatDate(date));
            return this;
        }

        @Override
        public HttpFields asImmutable()
        {
            return new Immutable(Arrays.copyOf(_fields, _size));
        }

        public Mutable clear()
        {
            _size = 0;
            return this;
        }

        /** Ensure that specific HttpField exists when the field may not exist or may
         * exist and be multi valued.  Multiple existing fields are merged into a
         * single field.
         * @param field The header to ensure is contained.  The field is used
         *              directly if possible so {@link PreEncodedHttpField}s can be
         *              passed.  If the value needs to be merged with existing values,
         *              then a new field is created.
         */
        public void ensureField(HttpField field)
        {
            // Is the field value multi valued?
            if (field.getValue().indexOf(',') < 0)
            {
                // Call Single valued computeEnsure with either String header name or enum HttpHeader
                if (field.getHeader() != null)
                    computeField(field.getHeader(), (h, l) -> computeEnsure(field, l));
                else
                    computeField(field.getName(), (h, l) -> computeEnsure(field, l));
            }
            else
            {
                // call multi valued computeEnsure with either String header name or enum HttpHeader
                if (field.getHeader() != null)
                    computeField(field.getHeader(), (h, l) -> computeEnsure(field, field.getValues(), l));
                else
                    computeField(field.getName(), (h, l) -> computeEnsure(field, field.getValues(), l));
            }
        }

        /**
         * Compute ensure field with a single value
         * @param ensure The field to ensure exists
         * @param fields The list of existing fields with the same header
         */
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
                if (v.length() > 0)
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

        /**
         * Compute ensure field with a multiple values
         * @param ensure The field to ensure exists
         * @param values The QuotedCSV parsed field values.
         * @param fields The list of existing fields with the same header
         */
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
                if (v.length() > 0)
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
                    if (value != null)
                        v.append(", ").append(value);
            }

            // return a merged header with missing ensured values added
            return new HttpField(ensure.getHeader(), ensure.getName(), v.toString());
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof Mutable))
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
        public int hashCode()
        {
            int hash = 0;
            for (int i = _fields.length; i-- > 0; )
                hash ^= _fields[i].hashCode();
            return hash;
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
                    Mutable.this.remove(--_index);
                }
            };
        }

        public ListIterator<HttpField> listIterator()
        {
            return new ListItr();
        }

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

        /**
         * Set a field.
         *
         * @param name the name of the field
         * @param value the value of the field. If null the field is cleared.
         * @return this builder
         */
        public Mutable put(String name, String value)
        {
            return (value == null)
                ? remove(name)
                : put(new HttpField(name, value));
        }

        public Mutable put(HttpHeader header, HttpHeaderValue value)
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
        public Mutable put(HttpHeader header, String value)
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
        public Mutable put(String name, List<String> list)
        {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(list, "list must not be null");
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
        public Mutable putDateField(HttpHeader name, long date)
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
        public Mutable putDateField(String name, long date)
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
        public Mutable putLongField(HttpHeader name, long value)
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
        public Mutable putLongField(String name, long value)
        {
            return put(name, Long.toString(value));
        }

        /**
         * <p>Computes a single field for the given HttpHeader and for existing fields with the same header.</p>
         *
         * <p>The compute function receives the field name and a list of fields with the same name
         * so that their values can be used to compute the value of the field that is returned
         * by the compute function.
         * If the compute function returns {@code null}, the fields with the given name are removed.</p>
         * <p>This method comes handy when you want to add an HTTP header if it does not exist,
         * or add a value if the HTTP header already exists, similarly to
         * {@link Map#compute(Object, BiFunction)}.</p>
         *
         * <p>This method can be used to {@link #put(HttpField) put} a new field (or blindly replace its value):</p>
         * <pre>
         * httpFields.computeField("X-New-Header",
         *     (name, fields) -&gt; new HttpField(name, "NewValue"));
         * </pre>
         *
         * <p>This method can be used to coalesce many fields into one:</p>
         * <pre>
         * // Input:
         * GET / HTTP/1.1
         * Host: localhost
         * Cookie: foo=1
         * Cookie: bar=2,baz=3
         * User-Agent: Jetty
         *
         * // Computation:
         * httpFields.computeField("Cookie", (name, fields) -&gt;
         * {
         *     // No cookies, nothing to do.
         *     if (fields == null)
         *         return null;
         *
         *     // Coalesces all cookies.
         *     String coalesced = fields.stream()
         *         .flatMap(field -&gt; Stream.of(field.getValues()))
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
         * </pre>
         *
         * <p>This method can be used to replace a field:</p>
         * <pre>
         * httpFields.computeField("X-Length", (name, fields) -&gt;
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
         * </pre>
         *
         * <p>This method can be used to remove a field:</p>
         * <pre>
         * httpFields.computeField("Connection", (name, fields) -&gt; null);
         * </pre>
         *
         * @param header the HTTP header
         * @param computeFn the compute function
         */
        public void computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
        {
            computeField(header, computeFn, (f, h) -> f.getHeader() == h);
        }

        /**
         * <p>Computes a single field for the given HTTP header name and for existing fields with the same name.</p>
         *
         * @param name the HTTP header name
         * @param computeFn the compute function
         * @see #computeField(HttpHeader, BiFunction)
         */
        public void computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
        {
            computeField(name, computeFn, HttpField::is);
        }

        private <T> void computeField(T header, BiFunction<T, List<HttpField>, HttpField> computeFn, BiPredicate<HttpField, T> matcher)
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
                return;
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
        }

        /**
         * Remove a field.
         *
         * @param name the field to remove
         * @return this builder
         */
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

        public Mutable remove(EnumSet<HttpHeader> fields)
        {
            for (int i = 0; i < _size; i++)
            {
                HttpField f = _fields[i];
                if (fields.contains(f.getHeader()))
                    remove(i--);
            }
            return this;
        }

        /**
         * Remove a field.
         *
         * @param name the field to remove
         * @return this builder
         */
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

        private String formatCsvExcludingExisting(QuotedCSV existing, String... values)
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

        private class ListItr implements ListIterator<HttpField>
        {
            int _cursor;       // index of next element to return
            int _current = -1;

            @Override
            public void add(HttpField field)
            {
                if (field == null)
                    return;

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
                Mutable.this.remove(_current);
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

    /**
     * HTTP Fields. A collection of HTTP header and or Trailer fields.
     *
     * <p>This class is not synchronized as it is expected that modifications will only be performed by a
     * single thread.
     *
     * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
     */
    class Immutable implements HttpFields
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
        public String get(String header)
        {
            // default impl overridden for efficiency
            for (HttpField f : _fields)
                if (f.is(header))
                    return f.getValue();
            return null;
        }

        @Override
        public String get(HttpHeader header)
        {
            // default impl overridden for efficiency
            for (HttpField f : _fields)
                if (f.getHeader() == header)
                    return f.getValue();
            return null;
        }

        @Override
        public HttpField getField(HttpHeader header)
        {
            // default impl overridden for efficiency
            for (HttpField f : _fields)
                if (f.getHeader() == header)
                    return f;
            return null;
        }

        @Override
        public HttpField getField(String name)
        {
            // default impl overridden for efficiency
            for (HttpField f : _fields)
                if (f.is(name))
                    return f;
            return null;
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
            int hash = 0;
            for (int i = _fields.length; i-- > 0; )
                hash ^= _fields[i].hashCode();
            return hash;
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
                    return _fields[_index++];
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
}
