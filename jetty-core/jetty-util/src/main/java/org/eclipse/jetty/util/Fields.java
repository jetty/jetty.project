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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>A container for name/value pairs, known as fields.</p>
 * <p>A {@link Field} is composed of a name string that can be case-sensitive
 * or case-insensitive (by specifying the option at the constructor) and
 * of a case-sensitive set of value strings.</p>
 * <p>The implementation of this class is not thread safe.</p>
 */
public class Fields implements Iterable<Fields.Field>
{
    public static final Fields EMPTY = new Fields(Collections.emptyMap());

    private final Map<String, Field> fields;

    /**
     * <p>Creates an empty, modifiable, case insensitive {@code Fields} instance.</p>
     */
    public Fields()
    {
        this(false);
    }

    /**
     * <p>Creates an empty, modifiable, case insensitive {@code Fields} instance.</p>**
     *
     * @param caseSensitive whether this {@code Fields} instance must be case sensitive
     */
    public Fields(boolean caseSensitive)
    {
        this(caseSensitive ? new LinkedHashMap<>() : new TreeMap<>(String::compareToIgnoreCase));
    }

    public Fields(Map<String, Field> fields)
    {
        this.fields = fields;
    }

    public Fields asImmutable()
    {
        return new Fields(Collections.unmodifiableMap(fields));
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (obj instanceof Fields that)
        {
            if (getSize() != that.getSize())
                return false;
            for (Map.Entry<String, Field> entry : fields.entrySet())
            {
                String name = entry.getKey();
                Field value = entry.getValue();
                if (!value.equals(that.get(name)))
                    return false;
            }
            return true;
        }
        return false;
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

    public Stream<Field> stream()
    {
        return fields.values().stream();
    }

    /**
     * @param name the field name
     * @return the {@link Field} with the given name, or null if no such field exists
     */
    public Field get(String name)
    {
        return fields.get(name);
    }

    /**
     * @param name the field name
     * @return the first value of the field with the given name, or null if no such field exists
     */
    public String getValue(String name)
    {
        Field field = get(name);
        if (field == null)
            return null;
        return field.getValue();
    }

    /**
     * @param name the field name
     * @return the values of the field with the given name, or null if no such field exists
     */
    public List<String> getValues(String name)
    {
        Field field = get(name);
        if (field == null)
            return null;
        return field.getValues();
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
        fields.put(name, field);
    }

    /**
     * <p>Inserts or replaces the given {@link Field}, mapped to the {@link Field#getName() field's name}</p>
     *
     * @param field the field to put
     */
    public void put(Field field)
    {
        if (field != null)
        {
            String s = field.getName();
            fields.put(s, field);
        }
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
        String key = name;
        fields.compute(key, (k, f) ->
        {
            if (f == null)
                // Preserve the case for the field name
                return new Field(name, value);
            else
                return new Field(f.getName(), f.getValues(), value);
        });
    }

    /**
     * <p>Adds the given field, storing it if none exists for the given name,
     * or adding all the values to the existing field with the given name.</p>
     *
     * @param field the field to add
     */
    public void add(Field field)
    {
        String s = field.getName();
        String key = s;
        fields.compute(key, (k, f) ->
        {
            if (f == null)
                return field;
            else
                return new Field(f.getName(), f.getValues(), field.getValues());
        });
    }

    public void addAll(Fields fields)
    {
        for (Field field : fields)
            add(field);
    }

    /**
     * <p>Removes the {@link Field} with the given name.</p>
     *
     * @param name the name of the field to remove
     * @return the removed field, or null if no such field existed
     */
    public Field remove(String name)
    {
        return fields.remove(name);
    }

    /**
     * <p>Empties this {@code Fields} instance from all fields.</p>
     *
     * @see #isEmpty()
     */
    public void clear()
    {
        fields.clear();
    }

    /**
     * @return whether this {@code Fields} instance is empty
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

    /**
     * @return the fields (name and values) of this instance copied into a {@code Map}
     */
    public Map<String, String[]> toStringArrayMap()
    {
        Map<String, String[]> result = new LinkedHashMap<>();
        fields.forEach((k, f) -> result.put(f.getName(), f.getValues().toArray(new String[0])));
        return result;
    }

    @Override
    public String toString()
    {
        return fields.values().stream()
            .map(Field::toString)
            .collect(Collectors.joining(",", "[", "]"));
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
            this(name, List.of(value));
        }

        private Field(String name, List<String> values, String... moreValues)
        {
            this(name, values, List.of(moreValues));
        }

        private Field(String name, List<String> values, List<String> moreValues)
        {
            this.name = name;
            List<String> list = new ArrayList<>(values.size() + moreValues.size());
            list.addAll(values);
            list.addAll(moreValues);
            this.values = List.copyOf(list);
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
            return Objects.hash(name, values);
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
            return value == null ? null : (Integer)Integer.parseInt(value);
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

    /**
     * <p>Combine two Fields</p>
     * @param a The base Fields or null
     * @param b The overlayed Fields or null
     * @return Fields, which may be empty, but never null.
     */
    public static Fields combine(Fields a, Fields b)
    {
        if (b == null || b.isEmpty())
            return a == null ? EMPTY : a;

        if (a == null || a.isEmpty())
            return b;

        Fields fields = new Fields();
        fields.addAll(a);
        fields.addAll(b);
        return fields;
    }

}
