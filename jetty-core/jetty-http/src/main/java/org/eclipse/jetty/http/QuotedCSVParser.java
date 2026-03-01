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

import org.eclipse.jetty.util.QuotedStringTokenizer;

/**
 * Implements a quoted comma-separated list parser
 * in accordance with <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-5.5">RFC9110 section 5.5</a>
 * and <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-5.6">RFC9110 section 5.6</a>.
 * OWS is removed and quoted characters ignored for parsing.
 *
 * @see "https://datatracker.ietf.org/doc/html/rfc9110#section-5.5"
 * @see "https://datatracker.ietf.org/doc/html/rfc9110#section-5.6"
 */
public abstract class QuotedCSVParser
{
    private enum State
    {
        VALUE, PARAM_NAME, PARAM_VALUE
    }

    public static final String DELIMITERS = ",;=";
    public static final QuotedStringTokenizer LIST_TOKENIZER = QuotedStringTokenizer.builder()
        .delimiters(DELIMITERS)
        .ignoreOptionalWhiteSpace()
        .allowEmbeddedQuotes()
        .returnDelimiters()
        .returnQuotes()
        .build();

    protected final boolean _keepQuotes;

    public QuotedCSVParser(boolean keepQuotes)
    {
        _keepQuotes = keepQuotes;
    }

    public static String quote(String s)
    {
        return LIST_TOKENIZER.quote(s);
    }

    public static String quoteIfNeeded(String s)
    {
        return LIST_TOKENIZER.quoteIfNeeded(s);
    }

    public static String unquote(String s)
    {
        return LIST_TOKENIZER.unquote(s);
    }

    /**
     * Add and parse a value string(s)
     *
     * @param value A value that may contain one or more Quoted CSV items.
     */
    public void addValue(String value)
    {
        if (value == null)
            return;

        // The parser does not actually use LIST_TOKENIZER as we wish to keep the tokens in the StringBuilder
        // and allow them to be mutated by the callbacks.

        StringBuilder buffer = new StringBuilder();

        int l = value.length();
        State state = State.VALUE;
        boolean inQuotes = false;
        boolean quoted = false;
        boolean sloshed = false;
        int nwsLength = 0;
        int lastLength = 0;
        int valueLength = -1;
        boolean paramsOnly = false;
        int paramName = -1;
        int paramValue = -1;

        for (int i = 0; i <= l; i++)
        {
            char c = i == l ? 0 : value.charAt(i);

            // Handle quoting https://tools.ietf.org/html/rfc7230#section-3.2.6
            if (inQuotes)
            {
                // ignore values without closed quotes
                if (c == 0)
                {
                    onComplianceViolation(HttpCompliance.Violation.BAD_QUOTES_IN_TOKEN, value);
                }
                else
                {
                    if (sloshed)
                        sloshed = false;
                    else
                    {
                        switch (c)
                        {
                            case '\\':
                                sloshed = true;
                                if (!_keepQuotes)
                                    continue;
                                break;
                            case '"':
                                inQuotes = false;
                                if (!_keepQuotes)
                                    continue;
                                break;
                            default:
                                break;
                        }
                    }

                    buffer.append(c);
                    nwsLength = buffer.length();
                    continue;
                }
            }

            // Handle common cases
            switch (c)
            {
                case ' ':
                case '\t':
                    if (buffer.length() > lastLength) // not leading OWS
                        buffer.append(c);
                    else if (state == State.PARAM_VALUE)
                        onComplianceViolation(HttpCompliance.Violation.WHITESPACE_IN_PARAMETER, value);
                    continue;

                case ';':
                    buffer.setLength(nwsLength); // trim following OWS
                    if (state == State.VALUE)
                    {
                        valueLength = buffer.length();
                        if (valueLength > 0)
                            parsedValue(buffer);
                    }
                    else if (valueLength > 0 || paramsOnly)
                    {
                        parsedParam(buffer, valueLength, paramName, paramValue);
                    }

                    nwsLength = buffer.length();
                    paramName = paramValue = -1;
                    buffer.append(c);
                    lastLength = ++nwsLength;
                    state = State.PARAM_NAME;
                    quoted = false;
                    continue;

                case ',':
                case 0:
                    if (nwsLength > 0 || quoted)
                    {
                        buffer.setLength(nwsLength); // trim following OWS
                        switch (state)
                        {
                            case VALUE:
                                valueLength = buffer.length();
                                if (valueLength > 0 || quoted)
                                {
                                    parsedValue(buffer);
                                    parsedValueAndParams(buffer);
                                }
                                break;
                            case PARAM_NAME:
                            case PARAM_VALUE:
                                if (valueLength > 0 || quoted || paramsOnly)
                                {
                                    parsedParam(buffer, valueLength, paramName, paramValue);
                                    if (valueLength > 0)
                                        parsedValueAndParams(buffer);
                                }
                                break;
                            default:
                                throw new IllegalStateException(state.toString());
                        }
                    }
                    buffer.setLength(0);
                    lastLength = 0;
                    nwsLength = 0;
                    valueLength = paramName = paramValue = -1;
                    state = State.VALUE;
                    paramsOnly = false;
                    quoted = false;
                    continue;

                case '=':
                    switch (state)
                    {
                        case VALUE:
                            // It wasn't really a value, it was a param name
                            paramName = 0;
                            if (nwsLength != buffer.length())
                                onComplianceViolation(HttpCompliance.Violation.WHITESPACE_IN_PARAMETER, value);

                            buffer.setLength(nwsLength); // trim following OWS
                            final String param = buffer.toString();
                            paramsOnly = !param.isEmpty();
                            buffer.setLength(0);
                            valueLength = 0;
                            buffer.append(param);
                            buffer.append(c);
                            lastLength = ++nwsLength;
                            state = State.PARAM_VALUE;
                            quoted = false;
                            continue;

                        case PARAM_NAME:
                            if (nwsLength != buffer.length())
                                onComplianceViolation(HttpCompliance.Violation.WHITESPACE_IN_PARAMETER, value);
                            buffer.setLength(nwsLength); // trim following OWS
                            buffer.append(c);
                            lastLength = ++nwsLength;
                            state = State.PARAM_VALUE;
                            continue;

                        case PARAM_VALUE:
                            if (paramValue < 0)
                                paramValue = nwsLength;
                            buffer.append(c);
                            nwsLength = buffer.length();
                            continue;

                        default:
                            throw new IllegalStateException(state.toString());
                    }

                case '"':
                    if (state == State.VALUE || state == State.PARAM_VALUE)
                    {
                        if (state == State.VALUE && !buffer.isEmpty() || state == State.PARAM_VALUE && paramValue >= 0)
                            openingQuoteInValue(value, i);

                        inQuotes = true;
                        quoted = true;
                        if (state == State.PARAM_VALUE)
                            paramValue = nwsLength;
                        if (_keepQuotes)
                            buffer.append(c);
                        nwsLength = buffer.length();
                        continue;
                    }
                    onComplianceViolation(HttpCompliance.Violation.BAD_QUOTES_IN_TOKEN, value);
                    // otherwise fall through to handle embedded quote as a normal character

                default:
                {
                    if (quoted)
                        onComplianceViolation(HttpCompliance.Violation.BAD_QUOTES_IN_TOKEN, value);

                    switch (state)
                    {
                        case VALUE:
                        {
                            buffer.append(c);
                            nwsLength = buffer.length();
                            continue;
                        }

                        case PARAM_NAME:
                        {
                            if (paramName < 0)
                                paramName = nwsLength;
                            buffer.append(c);
                            nwsLength = buffer.length();
                            continue;
                        }

                        case PARAM_VALUE:
                        {
                            if (paramValue < 0)
                                paramValue = nwsLength;
                            buffer.append(c);
                            nwsLength = buffer.length();
                            continue;
                        }

                        default:
                            throw new IllegalStateException(state.toString());
                    }
                }
            }
        }
    }

    protected void openingQuoteInValue(String value, int i)
    {
        onComplianceViolation(HttpCompliance.Violation.BAD_QUOTES_IN_TOKEN, value);
    }

    /**
     * Called when a value and it's parameters has been parsed
     *
     * @param buffer Containing the trimmed value and parameters
     */
    protected void parsedValueAndParams(StringBuilder buffer)
    {
    }

    /**
     * Called when a value has been parsed (prior to any parameters)
     *
     * @param buffer Containing the trimmed value, which may be mutated
     */
    protected void parsedValue(StringBuilder buffer)
    {
    }

    /**
     * Called when a parameter has been parsed
     *
     * @param buffer Containing the trimmed value and all parameters, which may be mutated
     * @param valueLength The length of the value
     * @param paramName The index of the start of the parameter just parsed
     * @param paramValue The index of the start of the parameter value just parsed, or -1
     */
    protected void parsedParam(StringBuilder buffer, int valueLength, int paramName, int paramValue)
    {
    }

    /**
     * Called when a parameter has been parsed with bad white space
     *
     * @param violation The violation
     * @deprecated use {@link #onComplianceViolation(ComplianceViolation, String)} instead
     */
    @Deprecated(since = "12.1.3", forRemoval = true)
    protected void onComplianceViolation(ComplianceViolation violation)
    {
        onComplianceViolation(violation, null);
    }

    /**
     * Called when a parameter has been parsed with bad white space
     */
    protected void onComplianceViolation(ComplianceViolation violation, String value)
    {
        throw new IllegalArgumentException(violation.getDescription() + (value != null ? ": " + value : ""));
    }
}
