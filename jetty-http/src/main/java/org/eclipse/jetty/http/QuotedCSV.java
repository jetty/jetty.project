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

package org.eclipse.jetty.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
