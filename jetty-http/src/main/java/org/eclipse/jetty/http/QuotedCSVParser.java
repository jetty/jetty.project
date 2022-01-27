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

/**
 * Implements a quoted comma separated list parser
 * in accordance with RFC7230.
 * OWS is removed and quoted characters ignored for parsing.
 *
 * @see "https://tools.ietf.org/html/rfc7230#section-3.2.6"
 * @see "https://tools.ietf.org/html/rfc7230#section-7"
 */
public abstract class QuotedCSVParser
{
    private enum State
    {
        VALUE, PARAM_NAME, PARAM_VALUE
    }

    protected final boolean _keepQuotes;

    public QuotedCSVParser(boolean keepQuotes)
    {
        _keepQuotes = keepQuotes;
    }

    public static String unquote(String s)
    {
        // handle trivial cases
        int l = s.length();
        if (s == null || l == 0)
            return s;

        // Look for any quotes
        int i = 0;
        for (; i < l; i++)
        {
            char c = s.charAt(i);
            if (c == '"')
                break;
        }
        if (i == l)
            return s;

        boolean quoted = true;
        boolean sloshed = false;
        StringBuffer buffer = new StringBuffer();
        buffer.append(s, 0, i);
        i++;
        for (; i < l; i++)
        {
            char c = s.charAt(i);
            if (quoted)
            {
                if (sloshed)
                {
                    buffer.append(c);
                    sloshed = false;
                }
                else if (c == '"')
                    quoted = false;
                else if (c == '\\')
                    sloshed = true;
                else
                    buffer.append(c);
            }
            else if (c == '"')
                quoted = true;
            else
                buffer.append(c);
        }
        return buffer.toString();
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

        StringBuffer buffer = new StringBuffer();

        int l = value.length();
        State state = State.VALUE;
        boolean quoted = false;
        boolean sloshed = false;
        int nwsLength = 0;
        int lastLength = 0;
        int valueLength = -1;
        int paramName = -1;
        int paramValue = -1;

        for (int i = 0; i <= l; i++)
        {
            char c = i == l ? 0 : value.charAt(i);

            // Handle quoting https://tools.ietf.org/html/rfc7230#section-3.2.6
            if (quoted && c != 0)
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
                            quoted = false;
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

            // Handle common cases
            switch (c)
            {
                case ' ':
                case '\t':
                    if (buffer.length() > lastLength) // not leading OWS
                        buffer.append(c);
                    continue;

                case '"':
                    quoted = true;
                    if (_keepQuotes)
                    {
                        if (state == State.PARAM_VALUE && paramValue < 0)
                            paramValue = nwsLength;
                        buffer.append(c);
                    }
                    else if (state == State.PARAM_VALUE && paramValue < 0)
                        paramValue = nwsLength;
                    nwsLength = buffer.length();
                    continue;

                case ';':
                    buffer.setLength(nwsLength); // trim following OWS
                    if (state == State.VALUE)
                    {
                        parsedValue(buffer);
                        valueLength = buffer.length();
                    }
                    else
                        parsedParam(buffer, valueLength, paramName, paramValue);
                    nwsLength = buffer.length();
                    paramName = paramValue = -1;
                    buffer.append(c);
                    lastLength = ++nwsLength;
                    state = State.PARAM_NAME;
                    continue;

                case ',':
                case 0:
                    if (nwsLength > 0)
                    {
                        buffer.setLength(nwsLength); // trim following OWS
                        switch (state)
                        {
                            case VALUE:
                                parsedValue(buffer);
                                valueLength = buffer.length();
                                break;
                            case PARAM_NAME:
                            case PARAM_VALUE:
                                parsedParam(buffer, valueLength, paramName, paramValue);
                                break;
                            default:
                                throw new IllegalStateException(state.toString());
                        }

                        parsedValueAndParams(buffer);
                    }
                    buffer.setLength(0);
                    lastLength = 0;
                    nwsLength = 0;
                    valueLength = paramName = paramValue = -1;
                    state = State.VALUE;
                    continue;

                case '=':
                    switch (state)
                    {
                        case VALUE:
                            // It wasn't really a value, it was a param name
                            paramName = 0;
                            buffer.setLength(nwsLength); // trim following OWS
                            final String param = buffer.toString();
                            buffer.setLength(0);
                            parsedValue(buffer);
                            valueLength = buffer.length();
                            buffer.append(param);
                            buffer.append(c);
                            lastLength = ++nwsLength;
                            state = State.PARAM_VALUE;
                            continue;

                        case PARAM_NAME:
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

                default:
                {
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

    /**
     * Called when a value and it's parameters has been parsed
     *
     * @param buffer Containing the trimmed value and parameters
     */
    protected void parsedValueAndParams(StringBuffer buffer)
    {
    }

    /**
     * Called when a value has been parsed (prior to any parameters)
     *
     * @param buffer Containing the trimmed value, which may be mutated
     */
    protected void parsedValue(StringBuffer buffer)
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
    protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
    {
    }
}
