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
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Implements a quoted comma-separated list of values
 * in accordance with <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-5.5">RFC9110 section 5.5</a>
 * and <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-5.6">RFC9110 section 5.6</a>.
 * OWS is removed and quoted characters ignored for parsing.
 *
 * @see "https://datatracker.ietf.org/doc/html/rfc9110#section-5.5"
 * @see "https://datatracker.ietf.org/doc/html/rfc9110#section-5.6"
 */
public class QuotedCSV extends QuotedCSVParser implements Iterable<String>
{
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
            LIST_TOKENIZER.quoteIfNeeded(builder, value);
        }
    }

    private final List<String> _values = new ArrayList<>();
    private final ComplianceViolation.Mode _compliance;
    private final ComplianceViolation.Listener _listener;

    public QuotedCSV(String... values)
    {
        this(true, values);
    }

    public QuotedCSV(boolean keepQuotes, String... values)
    {
        this(null, null, keepQuotes, values);
    }

    public QuotedCSV(ComplianceViolation.Mode compliance, ComplianceViolation.Listener listener, boolean keepQuotes, String... values)
    {
        super(keepQuotes);
        _compliance = compliance;
        _listener = listener;
        for (String v : values)
        {
            addValue(v);
        }
    }

    public QuotedCSV(HttpFields httpFields, boolean keepQuotes, String... values)
    {
        this(MutableHttpFields.copyHttpCompliance(httpFields), extractComplianceViolationListener(httpFields), keepQuotes, values);
    }

    private static ComplianceViolation.Listener extractComplianceViolationListener(HttpFields httpFields)
    {
        Supplier<ComplianceViolation.Listener> supplier = MutableHttpFields.copyComplianceListener(httpFields);
        return (supplier != null) ? supplier.get() : null;
    }

    @Override
    protected void parsedValueAndParams(StringBuilder buffer)
    {
        _values.add(buffer.toString());
    }

    @Override
    protected void parsedParam(StringBuilder buffer, int valueLength, int paramName, int paramValue)
    {
        // Handle no value on the first parameter
        if (valueLength == 0)
        {
            if (paramName == 0)
            {
                _values.add(buffer.toString());
            }
            else if (paramName > 0)
            {
                // replace last value
                int lastIdx = size() - 1;
                _values.set(lastIdx, buffer.toString());
            }
        }
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
        return Collections.unmodifiableList(_values);
    }

    public List<String> getMutableValues()
    {
        return _values;
    }

    @Override
    public Iterator<String> iterator()
    {
        return _values.iterator();
    }

    @Override
    protected void onComplianceViolation(ComplianceViolation violation, String value)
    {
        if (_compliance != null)
        {
            boolean allowed = _compliance.allows(violation);
            _listener.onComplianceViolation(new ComplianceViolation.Event(_compliance, violation, value, allowed));
            if (!allowed)
                throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "Invalid quoted: " + value);
        }
        else
        {
            super.onComplianceViolation(violation, value);
        }
    }

    public String asString()
    {
        if (_values.isEmpty())
            return null;
        if (_values.size() == 1)
            return _values.get(0);

        StringBuilder builder = new StringBuilder();
        join(builder, _values);
        return builder.toString();
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

    /**
     * @deprecated use {@link QuotedCSV} instead
     */
    @Deprecated(since = "12.1.6", forRemoval = true)
    public static class Compliant extends QuotedCSV
    {
        public Compliant(ComplianceViolation.Mode complianceMode, BiConsumer<ComplianceViolation, String> violationNotifier)
        {
            this(complianceMode, violationNotifier, true);
        }

        public Compliant(ComplianceViolation.Mode complianceMode, BiConsumer<ComplianceViolation, String> violationNotifier, boolean keepQuotes, String... values)
        {
            this(complianceMode, new ComplianceViolation.Listener()
            {
                @Override
                public void onComplianceViolation(ComplianceViolation.Event event)
                {
                    violationNotifier.accept(event.violation(), event.details());
                }
            }, keepQuotes, values);
        }

        private Compliant(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, boolean keepQuotes, String[] values)
        {
            super(complianceMode, listener, keepQuotes, values);
        }
    }

    public static class Etags extends Compliant
    {
        /**
         * @deprecated use {@link #Etags(ComplianceViolation.Mode, ComplianceViolation.Listener, String[])} instead.
         */
        @Deprecated(since = "12.1.6", forRemoval = true)
        public Etags(ComplianceViolation.Mode complianceMode, BiConsumer<ComplianceViolation, String> violationNotifier, String... values)
        {
            this(complianceMode, new ComplianceViolation.Listener()
            {
                @Override
                public void onComplianceViolation(ComplianceViolation.Event event)
                {
                    violationNotifier.accept(event.violation(), event.details());
                }
            }, values);
        }

        public Etags(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, String... values)
        {
            super(complianceMode, listener, true, values);
        }

        @Override
        protected void openingQuoteInValue(String value, int i)
        {
            if (i < 1 || Character.toLowerCase(value.charAt(i - 2)) != 'w' || value.charAt(i - 1) != '/')
                super.openingQuoteInValue(value, i);
        }
    }
}
