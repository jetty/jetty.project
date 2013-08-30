//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>A container for name/value pairs, known as fields.</p>
 * <p>A {@link Field} is composed of a case-insensitive name string and
 * of a case-sensitive set of value strings.</p>
 * <p>The implementation of this class is not thread safe.</p>
 */
public class Fields implements Iterable<Fields.Field>
{
    private final Map<String, Field> fields;

    /**
     * <p>Creates an empty modifiable {@link Fields} instance.</p>
     * @see #Fields(Fields, boolean)
     */
    public Fields()
    {
        fields = new LinkedHashMap<>();
    }

    /**
     * <p>Creates a {@link Fields} instance by copying the fields from the given
     * {@link Fields} and making it (im)mutable depending on the given {@code immutable} parameter</p>
     *
     * @param original the {@link Fields} to copy fields from
     * @param immutable whether this instance is immutable
     */
    public Fields(Fields original, boolean immutable)
    {
        Map<String, Field> copy = new LinkedHashMap<>();
        copy.putAll(original.fields);
        fields = immutable ? Collections.unmodifiableMap(copy) : copy;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Fields that = (Fields)obj;
        return fields.equals(that.fields);
    }

    @Override
    public int hashCode()
    {
        return fields.hashCode();
    }

    /**
     * @return a set of field names
     */
    public Set<String> names()
    {
        Set<String> result = new LinkedHashSet<>();
        for (Field field : fields.values())
            result.add(field.name());
        return result;
    }

    /**
     * @param name the field name
     * @return the {@link Field} with the given name, or null if no such field exists
     */
    public Field get(String name)
    {
        return fields.get(name.trim().toLowerCase(Locale.ENGLISH));
    }

    /**
     * <p>Inserts or replaces the given name/value pair as a single-valued {@link Field}.</p>
     *
     * @param name the field name
     * @param value the field value
     */
    public void put(String name, String value)
    {
        name = name.trim();
        // Preserve the case for the field name
        Field field = new Field(name, value);
        fields.put(name.toLowerCase(Locale.ENGLISH), field);
    }

    /**
     * <p>Inserts or replaces the given {@link Field}, mapped to the {@link Field#name() field's name}</p>
     *
     * @param field the field to put
     */
    public void put(Field field)
    {
        if (field != null)
            fields.put(field.name().toLowerCase(Locale.ENGLISH), field);
    }

    /**
     * <p>Adds the given value to a field with the given name,
     * creating a {@link Field} is none exists for the given name.</p>
     *
     * @param name the field name
     * @param value the field value to add
     */
    public void add(String name, String value)
    {
        name = name.trim();
        Field field = fields.get(name.toLowerCase(Locale.ENGLISH));
        if (field == null)
        {
            field = new Field(name, value);
            fields.put(name.toLowerCase(Locale.ENGLISH), field);
        }
        else
        {
            field = new Field(field.name(), field.values(), value);
            fields.put(name.toLowerCase(Locale.ENGLISH), field);
        }
    }

    /**
     * <p>Removes the {@link Field} with the given name</p>
     *
     * @param name the name of the field to remove
     * @return the removed field, or null if no such field existed
     */
    public Field remove(String name)
    {
        name = name.trim();
        return fields.remove(name.toLowerCase(Locale.ENGLISH));
    }

    /**
     * <p>Empties this {@link Fields} instance from all fields</p>
     * @see #isEmpty()
     */
    public void clear()
    {
        fields.clear();
    }

    /**
     * @return whether this {@link Fields} instance is empty
     */
    public boolean isEmpty()
    {
        return fields.isEmpty();
    }

    /**
     * @return the number of fields
     */
    public int size()
    {
        return fields.size();
    }

    /**
     * @return an iterator over the {@link Field}s present in this instance
     */
    @Override
    public Iterator<Field> iterator()
    {
        return fields.values().iterator();
    }

    @Override
    public String toString()
    {
        return fields.toString();
    }

    /**
     * <p>A named list of string values.</p>
     * <p>The name is case-sensitive and there must be at least one value.</p>
     */
    public static class Field
    {
        private final String name;
        private final String[] values;

        public Field(String name, String value)
        {
            this(name, new String[]{value});
        }

        private Field(String name, String[] values, String... moreValues)
        {
            this.name = name;
            this.values = new String[values.length + moreValues.length];
            System.arraycopy(values, 0, this.values, 0, values.length);
            System.arraycopy(moreValues, 0, this.values, values.length, moreValues.length);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Field that = (Field)obj;
            // Field names must be lowercase, thus we lowercase them before transmission, but keep them as is
            // internally. That's why we've to compare them case insensitive.
            return name.equalsIgnoreCase(that.name) && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode()
        {
            int result = name.toLowerCase(Locale.ENGLISH).hashCode();
            result = 31 * result + Arrays.hashCode(values);
            return result;
        }

        /**
         * @return the field's name
         */
        public String name()
        {
            return name;
        }

        /**
         * @return the first field's value
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
         * @return the field's values
         */
        public String[] values()
        {
            return values;
        }

        /**
         * @return whether the field has multiple values
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
