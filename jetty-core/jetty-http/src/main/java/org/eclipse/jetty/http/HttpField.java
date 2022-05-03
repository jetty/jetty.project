//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;

/**
 * An HTTP Field
 */
public class HttpField
{
    private static final String __zeroquality = "q=0";
    private final HttpHeader _header;
    private final String _name;
    private final String _value;
    // cached hashcode for case insensitive name
    private int hash = 0;

    public HttpField(HttpHeader header, String name, String value)
    {
        _header = header;
        if (_header != null && name == null)
            _name = _header.asString();
        else
            _name = Objects.requireNonNull(name, "name");
        _value = value;
    }

    public HttpField(HttpHeader header, String value)
    {
        this(header, header.asString(), value);
    }

    public HttpField(HttpHeader header, HttpHeaderValue value)
    {
        this(header, header.asString(), value.asString());
    }

    public HttpField(String name, String value)
    {
        this(HttpHeader.CACHE.get(name), name, value);
    }

    /**
     * Get field value parameters. Some field values can have parameters. This method separates the
     * value from the parameters and optionally populates a map with the parameters. For example:
     *
     * <PRE>
     *
     * FieldName : Value ; param1=val1 ; param2=val2
     *
     * </PRE>
     *
     * @param value The Field value, possibly with parameters.
     * @param parameters A map to populate with the parameters, or null
     * @return The value.
     */
    public static String getValueParameters(String value, Map<String, String> parameters)
    {
        if (value == null)
            return null;

        int i = value.indexOf(';');
        if (i < 0)
            return value;
        if (parameters == null)
            return value.substring(0, i).trim();

        StringTokenizer tok1 = new QuotedStringTokenizer(value.substring(i), ";", false, true);
        while (tok1.hasMoreTokens())
        {
            String token = tok1.nextToken();
            StringTokenizer tok2 = new QuotedStringTokenizer(token, "= ");
            if (tok2.hasMoreTokens())
            {
                String paramName = tok2.nextToken();
                String paramVal = null;
                if (tok2.hasMoreTokens())
                    paramVal = tok2.nextToken();
                parameters.put(paramName, paramVal);
            }
        }

        return value.substring(0, i).trim();
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
        return getValueParameters(value, parameters);
    }

    /**
     * Look for a value in a possible multi valued field
     *
     * @param search Values to search for (case insensitive)
     * @return True iff the value is contained in the field value entirely or
     * as an element of a quoted comma separated list. List element parameters (eg qualities) are ignored,
     * except if they are q=0, in which case the item itself is ignored.
     */
    public boolean contains(String search)
    {
        return contains(_value, search);
    }

    /**
     * Look for a value in a possible multi valued field
     *
     * @param value The field value to search in.
     * @param search Values to search for (case insensitive)
     * @return True iff the value is contained in the field value entirely or
     * as an element of a quoted comma separated list. List element parameters (eg qualities) are ignored,
     * except if they are q=0, in which case the item itself is ignored.
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
                case 0: // initial white space
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
                    break;

                case 1: // In token
                    switch (c)
                    {
                        case ',': // next field
                            // Have we matched the token?
                            if (match == search.length())
                                return true;
                            state = 0;
                            break;

                        case ';':
                            param = match >= 0 ? 0 : -1;
                            state = 5; // parameter
                            break;

                        default:
                            if (match > 0)
                            {
                                if (match < search.length())
                                    match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                                else if (c != ' ' && c != '\t')
                                    match = -1;
                            }
                            break;
                    }
                    break;

                case 2: // In Quoted token
                    switch (c)
                    {
                        case '\\': // quoted character
                            state = 3;
                            break;

                        case '"': // end quote
                            state = 4;
                            break;

                        default:
                            if (match >= 0)
                            {
                                if (match < search.length())
                                    match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                                else
                                    match = -1;
                            }
                    }
                    break;

                case 3: // In Quoted character in quoted token
                    if (match >= 0)
                    {
                        if (match < search.length())
                            match = c == StringUtil.asciiToLowerCase(search.charAt(match)) ? (match + 1) : -1;
                        else
                            match = -1;
                    }
                    state = 2;
                    break;

                case 4: // WS after end quote
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
                    break;

                case 5:  // parameter
                    switch (c)
                    {
                        case ',': // end token
                            // Have we matched the token and not q=0?
                            if (param != __zeroquality.length() && match == search.length())
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
                                if (param < __zeroquality.length())
                                    param = c == __zeroquality.charAt(param) ? (param + 1) : -1;
                                else if (c != '0' && c != '.')
                                    param = -1;
                            }
                    }
                    break;

                default:
                    throw new IllegalStateException();
            }
        }

        return param != __zeroquality.length() && match == search.length();
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof HttpField))
            return false;
        HttpField field = (HttpField)o;
        if (_header != field.getHeader())
            return false;
        if (!_name.equalsIgnoreCase(field.getName()))
            return false;
        return Objects.equals(_value, field.getValue());
    }

    public HttpHeader getHeader()
    {
        return _header;
    }

    public int getIntValue()
    {
        return Integer.parseInt(_value);
    }

    public long getLongValue()
    {
        return Long.parseLong(_value);
    }

    public String getLowerCaseName()
    {
        return _header != null ? _header.lowerCaseName() : StringUtil.asciiToLowerCase(_name);
    }

    public String getName()
    {
        return _name;
    }

    public String getValue()
    {
        return _value;
    }

    public String[] getValues()
    {
        if (_value == null)
            return null;

        QuotedCSV list = new QuotedCSV(false, _value);
        return list.getValues().toArray(new String[list.size()]);
    }

    @Override
    public int hashCode()
    {
        int vhc = Objects.hashCode(_value);
        if (_header == null)
            return vhc ^ nameHashCode();
        return vhc ^ _header.hashCode();
    }

    public boolean isSameName(HttpField field)
    {
        if (field == null)
            return false;
        if (field == this)
            return true;
        if (_header != null && _header == field.getHeader())
            return true;
        return _name.equalsIgnoreCase(field.getName());
    }

    public boolean is(String name)
    {
        return _name.equalsIgnoreCase(name);
    }

    private int nameHashCode()
    {
        int h = this.hash;
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
            this.hash = h;
        }
        return h;
    }

    @Override
    public String toString()
    {
        String v = getValue();
        return getName() + ": " + (v == null ? "" : v);
    }

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

        @Override
        public int getIntValue()
        {
            return (int)_long;
        }

        @Override
        public long getLongValue()
        {
            return _long;
        }
    }
}
