//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.function.ToIntFunction;

import org.eclipse.jetty.util.log.Log;

import static java.lang.Integer.MIN_VALUE;

/**
 * Implements a quoted comma separated list of quality values
 * in accordance with RFC7230 and RFC7231.
 * Values are returned sorted in quality order, with OWS and the
 * quality parameters removed.
 *
 * @see "https://tools.ietf.org/html/rfc7230#section-3.2.6"
 * @see "https://tools.ietf.org/html/rfc7230#section-7"
 * @see "https://tools.ietf.org/html/rfc7231#section-5.3.1"
 */
public class QuotedQualityCSV extends QuotedCSV implements Iterable<String>
{
    /**
     * Lambda to apply a most specific MIME encoding secondary ordering.
     *
     * @see "https://tools.ietf.org/html/rfc7231#section-5.3.2"
     */
    public static ToIntFunction<String> MOST_SPECIFIC_MIME_ORDERING = s ->
    {
        if ("*/*".equals(s))
            return 0;
        if (s.endsWith("/*"))
            return 1;
        if (s.indexOf(';') < 0)
            return 2;
        return 3;
    };

    private final List<Double> _quality = new ArrayList<>();
    private boolean _sorted = false;
    private final ToIntFunction<String> _secondaryOrdering;

    /**
     * Sorts values with equal quality according to the length of the value String.
     */
    public QuotedQualityCSV()
    {
        this((ToIntFunction)null);
    }

    /**
     * Sorts values with equal quality according to given order.
     *
     * @param preferredOrder Array indicating the preferred order of known values
     */
    public QuotedQualityCSV(String[] preferredOrder)
    {
        this((s) ->
        {
            for (int i = 0; i < preferredOrder.length; ++i)
            {
                if (preferredOrder[i].equals(s))
                    return preferredOrder.length - i;
            }

            if ("*".equals(s))
                return preferredOrder.length;

            return MIN_VALUE;
        });
    }

    /**
     * Orders values with equal quality with the given function.
     *
     * @param secondaryOrdering Function to apply an ordering other than specified by quality
     */
    public QuotedQualityCSV(ToIntFunction<String> secondaryOrdering)
    {
        this._secondaryOrdering = secondaryOrdering == null ? s -> 0 : secondaryOrdering;
    }

    @Override
    protected void parsedValue(StringBuffer buffer)
    {
        super.parsedValue(buffer);

        // Assume a quality of ONE
        _quality.add(1.0D);
    }

    @Override
    protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
    {
        if (paramName < 0)
        {
            if (buffer.charAt(buffer.length() - 1) == ';')
                buffer.setLength(buffer.length() - 1);
        }
        else if (paramValue >= 0 &&
            buffer.charAt(paramName) == 'q' && paramValue > paramName &&
            buffer.length() >= paramName && buffer.charAt(paramName + 1) == '=')
        {
            Double q;
            try
            {
                q = (_keepQuotes && buffer.charAt(paramValue) == '"')
                    ? Double.valueOf(buffer.substring(paramValue + 1, buffer.length() - 1))
                    : Double.valueOf(buffer.substring(paramValue));
            }
            catch (Exception e)
            {
                Log.getLogger(QuotedQualityCSV.class).ignore(e);
                q = 0.0D;
            }
            buffer.setLength(Math.max(0, paramName - 1));

            if (q != 1.0D)
                // replace assumed quality
                _quality.set(_quality.size() - 1, q);
        }
    }

    @Override
    public List<String> getValues()
    {
        if (!_sorted)
            sort();
        return _values;
    }

    @Override
    public Iterator<String> iterator()
    {
        if (!_sorted)
            sort();
        return _values.iterator();
    }

    protected void sort()
    {
        _sorted = true;

        Double last = 0.0D;
        int lastSecondaryOrder = Integer.MIN_VALUE;

        for (int i = _values.size(); i-- > 0; )
        {
            String v = _values.get(i);
            Double q = _quality.get(i);

            int compare = last.compareTo(q);
            if (compare > 0 || (compare == 0 && _secondaryOrdering.applyAsInt(v) < lastSecondaryOrder))
            {
                _values.set(i, _values.get(i + 1));
                _values.set(i + 1, v);
                _quality.set(i, _quality.get(i + 1));
                _quality.set(i + 1, q);
                last = 0.0D;
                lastSecondaryOrder = 0;
                i = _values.size();
                continue;
            }

            last = q;
            lastSecondaryOrder = _secondaryOrdering.applyAsInt(v);
        }

        int lastElement = _quality.size();
        while (lastElement > 0 && _quality.get(--lastElement).equals(0.0D))
        {
            _quality.remove(lastElement);
            _values.remove(lastElement);
        }
    }
}
