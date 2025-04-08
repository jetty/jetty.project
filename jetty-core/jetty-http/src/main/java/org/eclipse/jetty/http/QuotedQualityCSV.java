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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(QuotedQualityCSV.class);

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

    private final List<QualityValue> _qualities = new ArrayList<>();
    private QualityValue _lastQuality;
    private boolean _sorted = false;
    private final ToIntFunction<String> _secondaryOrdering;

    /**
     * Sorts values with equal quality according to the length of the value String.
     */
    public QuotedQualityCSV()
    {
        this((ToIntFunction<String>)null);
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

            return 0;
        });
    }

    /**
     * Sorts values with equal quality according to given order.
     *
     * @param preferredOrder Array indicating the preferred order of known values
     */
    public QuotedQualityCSV(List<String> preferredOrder)
    {
        this((s) ->
        {
            for (int i = 0; i < preferredOrder.size(); ++i)
            {
                if (preferredOrder.get(i).equals(s))
                    return preferredOrder.size() - i;
            }

            if ("*".equals(s))
                return preferredOrder.size();

            return 0;
        });
    }

    /**
     * Orders values with equal quality with the given function.
     *
     * @param secondaryOrdering Function to apply an ordering other than specified by quality, highest values are sorted first.
     */
    public QuotedQualityCSV(ToIntFunction<String> secondaryOrdering)
    {
        this._secondaryOrdering = secondaryOrdering == null ? s -> 0 : secondaryOrdering;
    }

    @Override
    protected void parsedValueAndParams(StringBuilder buffer)
    {
        super.parsedValueAndParams(buffer);

        // Collect full value with parameters
        _lastQuality = new QualityValue(buffer.toString(), _lastQuality._quality, _lastQuality._index);
        _qualities.set(_lastQuality._index, _lastQuality);
    }

    @Override
    protected void parsedValue(StringBuilder buffer)
    {
        super.parsedValue(buffer);

        _sorted = false;

        // This is the just the value, without parameters.
        // Assume a quality of ONE
        _lastQuality = new QualityValue(buffer.toString(), 1.0D, _qualities.size());
        _qualities.add(_lastQuality);
    }

    @Override
    protected void parsedParam(StringBuilder buffer, int valueLength, int paramName, int paramValue)
    {
        _sorted = false;

        if (paramName < 0)
        {
            if (buffer.charAt(buffer.length() - 1) == ';')
                buffer.setLength(buffer.length() - 1);
        }
        else if (paramValue >= 0 &&
            buffer.charAt(paramName) == 'q' && paramValue > paramName &&
            buffer.length() >= paramName && buffer.charAt(paramName + 1) == '=')
        {
            double q;
            try
            {
                q = (_keepQuotes && buffer.charAt(paramValue) == '"')
                    ? Double.valueOf(buffer.substring(paramValue + 1, buffer.length() - 1))
                    : Double.valueOf(buffer.substring(paramValue));
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
                q = 0.0D;
            }
            buffer.setLength(Math.max(0, paramName - 1));

            if (q != 1.0D)
            {
                _lastQuality = new QualityValue(buffer.toString(), q, _lastQuality._index);
                _qualities.set(_lastQuality._index, _lastQuality);
            }
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
        _values.clear();
        _qualities.stream()
            .filter((qv) -> qv._quality != 0.0D)
            .sorted()
            .map(QualityValue::getValue)
            .collect(Collectors.toCollection(() -> _values));
        _sorted = true;
    }

    public List<QualityValue> getQualityValues()
    {
        return _qualities.stream().sorted().toList();
    }

    /**
     * <p>A <em>quality value</em>, that is a value with an associated weight parameter, as
     * defined in <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.4.2">RFC 9110</a>.</p>
     * <p>A quality value appears in HTTP headers such as {@code Accept}, {@code Accept-Encoding},
     * etc. for example in this form:</p>
     * <pre>{@code
     * Accept-Encoding: gzip; q=1, br; q=0.5, zstd; q=0.1
     * }</pre>
     */
    public class QualityValue implements Comparable<QualityValue>
    {
        private final String _value;
        private final double _quality;
        private final int _index;

        private QualityValue(String value, double quality, int index)
        {
            _value = value;
            _quality = quality;
            _index = index;
        }

        /**
         * @return the value
         */
        public String getValue()
        {
            return _value;
        }

        /**
         * @return the weight
         */
        public double getWeight()
        {
            return _quality;
        }

        /**
         * @return whether the weight is greater than zero
         */
        public boolean isAcceptable()
        {
            return getWeight() > 0.0D;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(_quality, _value, _index);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof QualityValue that))
                return false;
            return _quality == that._quality && Objects.equals(_value, that._value) && _index == that._index;
        }

        @Override
        public int compareTo(QualityValue o)
        {
            // sort highest quality first
            int compare = Double.compare(o._quality, _quality);
            if (compare == 0)
            {
                // then sort secondary order highest first
                compare = Integer.compare(_secondaryOrdering.applyAsInt(o._value), _secondaryOrdering.applyAsInt(_value));
                if (compare == 0)
                    // then sort index lowest first
                    compare = -Integer.compare(o._index, _index);
            }
            return compare;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s,q=%.3f,i=%d]",
                getClass().getSimpleName(),
                hashCode(),
                getValue(),
                getWeight(),
                _index);
        }
    }
}
