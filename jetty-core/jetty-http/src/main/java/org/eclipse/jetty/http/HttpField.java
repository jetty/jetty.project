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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>An immutable class representing an HTTP header or trailer.</p>
 * <p>{@link HttpField} has a case-insensitive name and case sensitive value,
 * and may be multi-valued with each value separated by a comma.</p>
 *
 * @see HttpFields
 */
public class HttpField
{
    /**
     * A constant {@link QuotedStringTokenizer} configured for quoting/tokenizing {@code parameters} lists as defined by
     * <a href="https://www.rfc-editor.org/rfc/rfc9110#name-parameters">RFC9110</a>
     */
    public static final QuotedStringTokenizer PARAMETER_TOKENIZER = QuotedStringTokenizer.builder().delimiters(";").ignoreOptionalWhiteSpace().allowEmbeddedQuotes().returnQuotes().build();

    /**
     * A constant {@link QuotedStringTokenizer} configured for quoting/tokenizing a single {@code parameter} as defined by
     * <a href="https://www.rfc-editor.org/rfc/rfc9110#name-parameters">RFC9110</a>
     */
    public static final QuotedStringTokenizer NAME_VALUE_TOKENIZER = QuotedStringTokenizer.builder().delimiters("=").build();

    private static final String ZERO_QUALITY = "q=0";

    private final HttpHeader _header;
    private final String _name;
    private final String _value;
    private int _hash = 0;

    /**
     * <p>Creates a new {@link HttpField} with the given {@link HttpHeader},
     * name string and value string.</p>
     * <p>A {@code null} field value may be passed as parameter, and will
     * be converted to the empty string.
     * This allows the direct constructions of fields that have no value,
     * and/or {@link HttpField} subclasses that override {@link #getValue()}.</p>
     *
     * @param header the {@link HttpHeader} referencing a well-known HTTP header name;
     * may be {@code null} in case of an unknown or custom HTTP header name
     * @param name the field name; if {@code null}, then {@link HttpHeader#asString()} is used
     * @param value the field value; if {@code null}, the empty string will be used
     */
    public HttpField(HttpHeader header, String name, String value)
    {
        _header = header;
        if (_header != null && name == null)
            _name = _header.asString();
        else
            _name = Objects.requireNonNull(name, "name");
        _value = value != null ? value : "";
    }

    /**
     * <p>Creates a new {@link HttpField} with the given {@link HttpHeader}
     * and value string.</p>
     *
     * @param header the non-{@code null} {@link HttpHeader} referencing a well-known HTTP header name
     * @param value the field value; if {@code null}, the empty string will be used
     */
    public HttpField(HttpHeader header, String value)
    {
        this(header, header.asString(), value);
    }

    /**
     * <p>Creates a new {@link HttpField} with the given {@link HttpHeader}
     * and value.</p>
     *
     * @param header the non-{@code null} {@link HttpHeader} referencing a well-known HTTP header name
     * @param value the field value; if {@code null}, the empty string will be used
     */
    public HttpField(HttpHeader header, HttpHeaderValue value)
    {
        this(header, header.asString(), value != null ? value.asString() : null);
    }

    /**
     * <p>Creates a new {@link HttpField} with the given name string
     * and value string.</p>
     *
     * @param name the non-{@code null} field name
     * @param value the field value; if {@code null}, the empty string will be used
     */
    public HttpField(String name, String value)
    {
        this(HttpHeader.CACHE.get(name), name, value);
    }

    /**
     * <p>Returns the field value and its parameters.</p>
     * <p>A field value may have parameters, typically separated by {@code ;}, for example</p>
     * <pre>{@code
     * Content-Type: text/plain; charset=UTF-8
     * Accept: text/html, text/plain; q=0.5
     * }</pre>
     *
     * @param valueParams the field value, possibly with parameters
     * @param parameters An output map to populate with the parameters,
     * or {@code null} to strip the parameters
     * @return the field value without parameters
     * @see #stripParameters(String)
     */
    public static String getValueParameters(String valueParams, Map<String, String> parameters)
    {
        if (valueParams == null)
            return null;

        Iterator<String> tokens = PARAMETER_TOKENIZER.tokenize(valueParams);
        if (!tokens.hasNext())
            return null;

        String value = tokens.next();
        if (parameters != null)
        {
            while (tokens.hasNext())
            {
                String token = tokens.next();

                Iterator<String> nameValue = NAME_VALUE_TOKENIZER.tokenize(token);
                if (nameValue.hasNext())
                {
                    String paramName = nameValue.next();
                    String paramVal = null;
                    if (nameValue.hasNext())
                        paramVal = nameValue.next();
                    parameters.put(paramName, paramVal);
                }
            }
        }

        return value;
    }

    /**
     * <p>Returns the field value, stripped of its parameters.</p>
     *
     * @param value the field value, possibly with parameters
     * @return the field value without parameters
     * @see #getValueParameters(String, Map)
     */
    public static String stripParameters(String value)
    {
        return getValueParameters(value, null);
    }

    /**
     * @deprecated use {@link #getValueParameters(String, Map)} instead
     */
    @Deprecated
    public static String valueParameters(String value, Map<String, String> parameters)
    {
        return getValueParameters(value, parameters);
    }

    /**
     * <p>Returns whether this field value, possibly multi-valued,
     * contains the specified search string, case-insensitively.</p>
     * <p>Only values, and not parameters, are compared with the
     * search string.</p>
     *
     * @param search the string to search for
     * @return whether this field value, possibly multi-valued,
     * contains the specified search string
     */
    public boolean contains(String search)
    {
        return contains(getValue(), search);
    }

    /**
     * <p>Returns whether the given value, possibly multi-valued,
     * contains the specified search string, case-insensitively.</p>
     * <p>Only values, and not parameters, are compared with the
     * search string.</p>
     *
     * @param value the value string to search into
     * @param search the string to search for
     * @return whether the given value, possibly multi-valued,
     * contains the specified search string
     */
    public static boolean contains(String value, String search)
    {
        if (search == null)
            return value == null;
        if (search.isEmpty())
            return false;
        if (value == null)
            return false;
        if (search.equalsIgnoreCase(value))
            return true;

        int state = 0;
        int match = 0;
        int param = 0;

        for (int i = 0; i < value.length(); i++)
        {
            char c = StringUtil.asciiToLowerCase(value.charAt(i));
            switch (state)
            {
                case 0 -> // initial white space
                {
                    switch (c)
                    {
                        case '"': // open quote
                            match = 0;
                            state = 2;
                            break;

                        case ',': // ignore leading empty field
                            break;

                        case ';': // ignore leading empty field parameter
                            param = -1;
                            match = -1;
                            state = 5;
                            break;

                        case ' ': // more white space
                        case '\t':
                            break;

                        default: // character
                            match = c == StringUtil.asciiToLowerCase(search.charAt(0)) ? 1 : -1;
                            state = 1;
                            break;
                    }
                }
                case 1 -> // In token
                {
                    switch (c)
                    {
                        case ',' -> // next field
                        {
                            // Have we matched the token?
                            if (match == search.length())
                                return true;
                            state = 0;
                        }
                        case ';' ->
                        {
                            param = match >= 0 ? 0 : -1;
                            state = 5; // parameter
                        }
                        default ->
                        {
                            if (match > 0)
                            {
                                if (match < search.length())
                                    match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                                else if (c != ' ' && c != '\t')
                                    match = -1;
                            }
                        }
                    }
                }
                case 2 -> // In Quoted token
                {
                    switch (c)
                    {
                        case '\\' -> state = 3; // quoted character
                        case '"' -> state = 4;  // end quote
                        default ->
                        {
                            if (match >= 0)
                            {
                                if (match < search.length())
                                    match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                                else
                                    match = -1;
                            }
                        }
                    }
                }
                case 3 -> // In Quoted character in quoted token
                {
                    if (match >= 0)
                    {
                        if (match < search.length())
                            match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                        else
                            match = -1;
                    }
                    state = 2;
                }
                case 4 -> // WS after end quote
                {
                    switch (c)
                    {
                        case ' ': // white space
                        case '\t': // white space
                            break;

                        case ';':
                            state = 5; // parameter
                            break;

                        case ',': // end token
                            // Have we matched the token?
                            if (match == search.length())
                                return true;
                            state = 0;
                            break;

                        default:
                            // This is an illegal token, just ignore
                            match = -1;
                    }
                }
                case 5 -> // parameter
                {
                    switch (c)
                    {
                        case ',': // end token
                            // Have we matched the token and not q=0?
                            if (param != ZERO_QUALITY.length() && match == search.length())
                                return true;
                            param = 0;
                            state = 0;
                            break;

                        case ' ': // white space
                        case '\t': // white space
                            break;

                        default:
                            if (param >= 0)
                            {
                                if (param < ZERO_QUALITY.length())
                                    param = c == ZERO_QUALITY.charAt(param) ? (param + 1) : -1;
                                else if (c != '0' && c != '.')
                                    param = -1;
                            }
                    }
                }
                default -> throw new IllegalStateException();
            }
        }

        return param != ZERO_QUALITY.length() && match == search.length();
    }

    /**
     * Look for a value as the last value in a possible multivalued field
     * Parameters and specifically quality parameters are not considered.
     * @param search Values to search for (case-insensitive)
     * @return True iff the value is contained in the field value entirely or
     * as the last element of a quoted comma separated list.
     */
    public boolean containsLast(String search)
    {
        return containsLast(getValue(), search);
    }

    /**
     * Look for the last value in a possible multivalued field
     * Parameters and specifically quality parameters are not considered.
     * @param value The field value to search in.
     * @param search Values to search for (case-insensitive)
     * @return True iff the value is contained in the field value entirely or
     * as the last element of a quoted comma separated list.
     */
    public static boolean containsLast(String value, String search)
    {
        if (search == null)
            return value == null;
        if (search.isEmpty())
            return false;
        if (value == null)
            return false;
        if (search.equalsIgnoreCase(value))
            return true;

        if (value.endsWith(search))
        {
            int i = value.length() - search.length() - 1;
            while (i >= 0)
            {
                char c = value.charAt(i--);
                if (c == ',')
                    return true;
                if (c != ' ')
                    return false;
            }
            return true;
        }

        QuotedCSV csv = new QuotedCSV(false, value);
        List<String> values = csv.getValues();
        return !values.isEmpty() && search.equalsIgnoreCase(values.get(values.size() - 1));
    }

    @Override
    public int hashCode()
    {
        int vhc = Objects.hashCode(getValue());
        if (_header == null)
            return vhc ^ nameHashCode();
        return vhc ^ _header.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof HttpField field))
            return false;
        if (_header != field.getHeader())
            return false;
        if (!_name.equalsIgnoreCase(field.getName()))
            return false;
        return Objects.equals(getValue(), field.getValue());
    }

    /**
     * Get the {@link HttpHeader} of this field, or {@code null}.
     * @return the {@link HttpHeader} of this field, or {@code null}
     */
    public HttpHeader getHeader()
    {
        return _header;
    }

    /**
     * @return the value of this field as an {@code int}
     * @throws NumberFormatException if the value cannot be parsed as an {@code int}
     */
    public int getIntValue()
    {
        return Integer.parseInt(getValue());
    }

    /**
     * @return the value of this field as an {@code long}
     * @throws NumberFormatException if the value cannot be parsed as an {@code long}
     */
    public long getLongValue()
    {
        return Long.parseLong(getValue());
    }

    /**
     * Get the field name in lower-case.
     * @return the field name in lower-case
     */
    public String getLowerCaseName()
    {
        return _header != null ? _header.lowerCaseName() : StringUtil.asciiToLowerCase(_name);
    }

    /**
     * Get the field name.
     * @return the field name
     */
    public String getName()
    {
        return _name;
    }

    /**
     * Get the field value.
     * @return the field value
     */
    public String getValue()
    {
        return _value;
    }

    /**
     * @return the field values as a {@code String[]}
     * @see #getValueList()
     */
    public String[] getValues()
    {
        List<String> values = getValueList();
        if (values == null)
            return null;
        return values.toArray(String[]::new);
    }

    /**
     * <p>Returns a list of the field values.</p>
     * <p>If the field value is multi-valued, the encoded field value is split
     * into multiple values using {@link QuotedCSV} and the different values
     * returned in a list.</p>
     * <p>If the field value is single-valued, the value is wrapped into a list.</p>
     *
     * @return a list of the field values
     */
    public List<String> getValueList()
    {
        String value = getValue();
        if (value == null)
            return null;
        QuotedCSV list = new QuotedCSV(false, value);
        return list.getValues();
    }

    /**
     * <p>Returns whether this field has the same name as the given field.</p>
     * <p>The comparison of field name is case-insensitive via
     * {@link #is(String)}.</p>
     *
     * @param field the field to compare the name to
     * @return whether this field has the same name as the given field
     */
    public boolean isSameName(HttpField field)
    {
        if (field == null)
            return false;
        if (field == this)
            return true;
        if (_header != null && _header == field.getHeader())
            return true;
        return is(field.getName());
    }

    /**
     * <p>Returns whether this field name is the same as the given string.</p>
     * <p>The comparison of field name is case-insensitive.</p>
     *
     * @param name the field name to compare to
     * @return whether this field name is the same as the given string
     */
    public boolean is(String name)
    {
        return _name.equalsIgnoreCase(name);
    }

    /**
     * Return a {@link HttpField} without a given value (case-insensitive)
     * @param value The value to remove
     * @return A new {@link HttpField} if the value was removed, but others remain; this {@link HttpField} if it
     *         did not contain the value; or {@code null} if the value was the only value.
     */
    public HttpField withoutValue(String value)
    {
        if (_value.length() < value.length())
            return this;

        if (_value.equalsIgnoreCase(value))
            return null;

        if (!contains(value))
            return this;

        QuotedCSV csv = new QuotedCSV(false, _value);
        for (Iterator<String> i = csv.iterator(); i.hasNext();)
        {
            if (i.next().equalsIgnoreCase(value))
                i.remove();
        }

        return new HttpField(_header, _name, csv.asString());
    }

    /**
     * Return a {@link HttpField} with a given value (case-insensitive) ensured
     * @param value The value to ensure
     * @return A new {@link HttpField} if the value was added or this {@link HttpField} if it did contain the value
     */
    public HttpField withValue(String value)
    {
        if (contains(value))
            return this;
        else
            return new HttpField(getHeader(), _name, _value + "," + value);
    }

    /**
     * Return a {@link HttpField} with given values (case-insensitive) ensured
     * @param values The values to ensure
     * @return A new {@link HttpField} if the value was added or this {@link HttpField} if it did contain the value
     */
    public HttpField withValues(String... values)
    {
        HttpField field = this;
        for (String value : values)
            field = field.withValue(value);
        return field;
    }

    private int nameHashCode()
    {
        int h = this._hash;
        int len = _name.length();
        if (h == 0 && len > 0)
        {
            for (int i = 0; i < len; i++)
            {
                // simple case insensitive hash
                char c = _name.charAt(i);
                // assuming us-ascii (per last paragraph on http://tools.ietf.org/html/rfc7230#section-3.2.4)
                if ((c >= 'a' && c <= 'z'))
                    c -= 0x20;
                h = 31 * h + c;
            }
            this._hash = h;
        }
        return h;
    }

    @Override
    public String toString()
    {
        String v = getValue();
        return getName() + ": " + (v == null ? "" : v);
    }

    /**
     * <p>A specialized {@link HttpField} whose value is an {@code int}.</p>
     */
    public static class IntValueHttpField extends HttpField
    {
        private final int _int;

        public IntValueHttpField(HttpHeader header, String name, String value, int intValue)
        {
            super(header, name, value);
            _int = intValue;
        }

        public IntValueHttpField(HttpHeader header, String name, String value)
        {
            this(header, name, value, Integer.parseInt(value));
        }

        public IntValueHttpField(HttpHeader header, String name, int intValue)
        {
            this(header, name, Integer.toString(intValue), intValue);
        }

        public IntValueHttpField(HttpHeader header, int value)
        {
            this(header, header.asString(), value);
        }

        public IntValueHttpField(String header, int value)
        {
            this(HttpHeader.CACHE.get(header), header, value);
        }

        @Override
        public int getIntValue()
        {
            return _int;
        }

        @Override
        public long getLongValue()
        {
            return _int;
        }
    }

    /**
     * <p>A specialized {@link HttpField} whose value is a {@code long}.</p>
     */
    public static class LongValueHttpField extends HttpField
    {
        private final long _long;

        public LongValueHttpField(HttpHeader header, String name, String value, long longValue)
        {
            super(header, name, value);
            _long = longValue;
        }

        public LongValueHttpField(HttpHeader header, String name, String value)
        {
            this(header, name, value, Long.parseLong(value));
        }

        public LongValueHttpField(HttpHeader header, String name, long value)
        {
            this(header, name, Long.toString(value), value);
        }

        public LongValueHttpField(HttpHeader header, long value)
        {
            this(header, header.asString(), value);
        }

        public LongValueHttpField(String header, long value)
        {
            this(HttpHeader.CACHE.get(header), header, value);
        }

        @Override
        public int getIntValue()
        {
            return Math.toIntExact(_long);
        }

        @Override
        public long getLongValue()
        {
            return _long;
        }
    }

    static class MultiHttpField extends HttpField
    {
        private final List<String> _list;

        public MultiHttpField(String name, List<String> list)
        {
            super(name, buildValue(list));
            _list = list;
        }

        private static String buildValue(List<String> list)
        {
            StringBuilder builder = null;
            for (String v : list)
            {
                if (StringUtil.isBlank(v))
                    throw new IllegalArgumentException("blank element");
                if (builder == null)
                    builder = new StringBuilder(list.size() * (v == null ? 5 : v.length()) * 2);
                else
                    builder.append(", ");
                builder.append(v);
            }

            return builder == null ? null : builder.toString();
        }

        @Override
        public List<String> getValueList()
        {
            return _list;
        }

        @Override
        public boolean contains(String search)
        {
            for (String v : _list)
                if (StringUtil.asciiEqualsIgnoreCase(v, search))
                    return true;
            return false;
        }
    }
}
