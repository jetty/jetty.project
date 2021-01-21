//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>A container for name/value pairs, known as fields.</p>
 * <p>A {@link Field} is composed of a name string that can be case-sensitive
 * or case-insensitive (by specifying the option at the constructor) and
 * of a case-sensitive set of value strings.</p>
 * <p>The implementation of this class is not thread safe.</p>
 */
public class Fields implements Iterable<Fields.Field>
{
    private final boolean caseSensitive;
    private final Map<String, Field> fields;

    /**
     * <p>Creates an empty, modifiable, case insensitive {@link Fields} instance.</p>
     *
     * @see #Fields(Fields, boolean)
     */
    public Fields()
    {
        this(false);
    }

    /**
     * <p>Creates an empty, modifiable, case insensitive {@link Fields} instance.</p>
     *
     * @param caseSensitive whether this {@link Fields} instance must be case sensitive
     * @see #Fields(Fields, boolean)
     */
    public Fields(boolean caseSensitive)
    {
        this.caseSensitive = caseSensitive;
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
        this.caseSensitive = original.caseSensitive;
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
        if (getSize() != that.getSize())
            return false;
        if (caseSensitive != that.caseSensitive)
            return false;
        for (Map.Entry<String, Field> entry : fields.entrySet())
        {
            String name = entry.getKey();
            Field value = entry.getValue();
            if (!value.equals(that.get(name), caseSensitive))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        return fields.hashCode();
    }

    /**
     * @return a set of field names
     */
    public Set<String> getNames()
    {
        Set<String> result = new LinkedHashSet<>();
        for (Field field : fields.values())
        {
            result.add(field.getName());
        }
        return result;
    }

    private String normalizeName(String name)
    {
        return caseSensitive ? name : name.toLowerCase(Locale.ENGLISH);
    }

    /**
     * @param name the field name
     * @return the {@link Field} with the given name, or null if no such field exists
     */
    public Field get(String name)
    {
        return fields.get(normalizeName(name));
    }

    /**
     * <p>Inserts or replaces the given name/value pair as a single-valued {@link Field}.</p>
     *
     * @param name the field name
     * @param value the field value
     */
    public void put(String name, String value)
    {
        // Preserve the case for the field name
        Field field = new Field(name, value);
        fields.put(normalizeName(name), field);
    }

    /**
     * <p>Inserts or replaces the given {@link Field}, mapped to the {@link Field#getName() field's name}</p>
     *
     * @param field the field to put
     */
    public void put(Field field)
    {
        if (field != null)
            fields.put(normalizeName(field.getName()), field);
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
        String key = normalizeName(name);
        Field field = fields.get(key);
        if (field == null)
        {
            // Preserve the case for the field name
            field = new Field(name, value);
            fields.put(key, field);
        }
        else
        {
            field = new Field(field.getName(), field.getValues(), value);
            fields.put(key, field);
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
        return fields.remove(normalizeName(name));
    }

    /**
     * <p>Empties this {@link Fields} instance from all fields</p>
     *
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
    public int getSize()
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
        private final List<String> values;

        public Field(String name, String value)
        {
            this(name, Collections.singletonList(value));
        }

        private Field(String name, List<String> values, String... moreValues)
        {
            this.name = name;
            List<String> list = new ArrayList<>(values.size() + moreValues.length);
            list.addAll(values);
            list.addAll(Arrays.asList(moreValues));
            this.values = Collections.unmodifiableList(list);
        }

        @SuppressWarnings("ReferenceEquality")
        public boolean equals(Field that, boolean caseSensitive)
        {
            if (this == that)
                return true;
            if (that == null)
                return false;
            if (caseSensitive)
                return equals(that);
            return name.equalsIgnoreCase(that.name) && values.equals(that.values);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Field that = (Field)obj;
            return name.equals(that.name) && values.equals(that.values);
        }

        @Override
        public int hashCode()
        {
            int result = name.hashCode();
            result = 31 * result + values.hashCode();
            return result;
        }

        /**
         * @return the field's name
         */
        public String getName()
        {
            return name;
        }

        /**
         * @return the first field's value
         */
        public String getValue()
        {
            return values.get(0);
        }

        /**
         * <p>Attempts to convert the result of {@link #getValue()} to an integer,
         * returning it if the conversion is successful; returns null if the
         * result of {@link #getValue()} is null.</p>
         *
         * @return the result of {@link #getValue()} converted to an integer, or null
         * @throws NumberFormatException if the conversion fails
         */
        public Integer getValueAsInt()
        {
            final String value = getValue();
            return value == null ? null : Integer.valueOf(value);
        }

        /**
         * @return the field's values
         */
        public List<String> getValues()
        {
            return values;
        }

        /**
         * @return whether the field has multiple values
         */
        public boolean hasMultipleValues()
        {
            return values.size() > 1;
        }

        @Override
        public String toString()
        {
            return String.format("%s=%s", name, values);
        }
    }
}
