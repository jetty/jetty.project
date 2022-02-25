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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.QuotedStringTokenizer;

/**
 * Implements a quoted comma separated list of values
 * in accordance with RFC7230.
 * OWS is removed and quoted characters ignored for parsing.
 *
 * @see "https://tools.ietf.org/html/rfc7230#section-3.2.6"
 * @see "https://tools.ietf.org/html/rfc7230#section-7"
 */
public class QuotedCSV extends QuotedCSVParser implements Iterable<String>
{
    /**
     * ABNF from RFC 2616, RFC 822, and RFC 6455 specified characters requiring quoting.
     */
    public static final String ABNF_REQUIRED_QUOTING = "\"'\\\n\r\t\f\b%+ ;=,";

    /**
     * Join a list into Quoted CSV string
     *
     * @param values A list of values
     * @return A Quoted Comma Separated Value list
     */
    public static String join(List<String> values)
    {
        // no value list
        if (values == null)
            return null;

        int size = values.size();
        // empty value list
        if (size <= 0)
            return "";

        // simple return
        if (size == 1)
            return values.get(0);

        StringBuilder ret = new StringBuilder();
        join(ret, values);
        return ret.toString();
    }

    /**
     * Join a list into Quoted CSV string
     *
     * @param values A list of values
     * @return A Quoted Comma Separated Value list
     */
    public static String join(String... values)
    {
        if (values == null)
            return null;

        // empty value list
        if (values.length <= 0)
            return "";

        // simple return
        if (values.length == 1)
            return values[0];

        StringBuilder ret = new StringBuilder();
        join(ret, Arrays.asList(values));
        return ret.toString();
    }

    /**
     * Join a list into Quoted CSV StringBuilder
     *
     * @param builder A builder to join the list into
     * @param values A list of values
     */
    public static void join(StringBuilder builder, List<String> values)
    {
        if (values == null || values.isEmpty())
            return;

        // join it with commas
        boolean needsDelim = false;
        for (String value : values)
        {
            if (needsDelim)
                builder.append(", ");
            else
                needsDelim = true;
            QuotedStringTokenizer.quoteIfNeeded(builder, value, ABNF_REQUIRED_QUOTING);
        }
    }

    protected final List<String> _values = new ArrayList<>();

    public QuotedCSV(String... values)
    {
        this(true, values);
    }

    public QuotedCSV(boolean keepQuotes, String... values)
    {
        super(keepQuotes);
        for (String v : values)
        {
            addValue(v);
        }
    }

    @Override
    protected void parsedValueAndParams(StringBuffer buffer)
    {
        _values.add(buffer.toString());
    }

    public int size()
    {
        return _values.size();
    }

    public boolean isEmpty()
    {
        return _values.isEmpty();
    }

    public List<String> getValues()
    {
        return _values;
    }

    @Override
    public Iterator<String> iterator()
    {
        return _values.iterator();
    }

    @Override
    public String toString()
    {
        List<String> list = new ArrayList<>();
        for (String s : this)
        {
            list.add(s);
        }
        return list.toString();
    }
}
