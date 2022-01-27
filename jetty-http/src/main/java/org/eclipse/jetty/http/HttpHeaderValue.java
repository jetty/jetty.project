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

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;

/**
 *
 */
public enum HttpHeaderValue
{
    CLOSE("close"),
    CHUNKED("chunked"),
    GZIP("gzip"),
    IDENTITY("identity"),
    KEEP_ALIVE("keep-alive"),
    CONTINUE("100-continue"),
    PROCESSING("102-processing"),
    TE("TE"),
    BYTES("bytes"),
    NO_CACHE("no-cache"),
    UPGRADE("Upgrade");

    public static final Index<HttpHeaderValue> CACHE = new Index.Builder<HttpHeaderValue>()
        .caseSensitive(false)
        .withAll(HttpHeaderValue.values(), HttpHeaderValue::toString)
        .build();

    private final String _string;
    private final ByteBuffer _buffer;

    HttpHeaderValue(String s)
    {
        _string = s;
        _buffer = BufferUtil.toBuffer(s);
    }

    public ByteBuffer toBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    public boolean is(String s)
    {
        return _string.equalsIgnoreCase(s);
    }

    public String asString()
    {
        return _string;
    }

    @Override
    public String toString()
    {
        return _string;
    }

    private static final EnumSet<HttpHeader> __known =
        EnumSet.of(HttpHeader.CONNECTION,
            HttpHeader.TRANSFER_ENCODING,
            HttpHeader.CONTENT_ENCODING);

    public static boolean hasKnownValues(HttpHeader header)
    {
        if (header == null)
            return false;
        return __known.contains(header);
    }

    /**
     * Parse an unquoted comma separated list of index keys.
     * @param value A string list of index keys, separated with commas and possible white space
     * @param found The function to call for all found index entries. If the function returns false parsing is halted.
     * @return true if parsing completed normally and all found index items returned true from the found function.
     */
    public static boolean parseCsvIndex(String value, Function<HttpHeaderValue, Boolean> found)
    {
        return parseCsvIndex(value, found, null);
    }

    /**
     * Parse an unquoted comma separated list of index keys.
     * @param value A string list of index keys, separated with commas and possible white space
     * @param found The function to call for all found index entries. If the function returns false parsing is halted.
     * @param unknown The function to call for foound unknown entries. If the function returns false parsing is halted.
     * @return true if parsing completed normally and all found index items returned true from the found function.
     */
    public static boolean parseCsvIndex(String value, Function<HttpHeaderValue, Boolean> found, Function<String, Boolean> unknown)
    {
        if (StringUtil.isBlank(value))
            return true;
        int next = 0;
        parsing: while (next < value.length())
        {
            // Look for the best fit next token
            HttpHeaderValue token = CACHE.getBest(value, next, value.length() - next);

            // if a token is found
            if (token != null)
            {
                // check that it is only followed by whatspace, EOL and/or comma
                int i = next + token.toString().length();
                loop: while (true)
                {
                    if (i >= value.length())
                        return found.apply(token);
                    switch (value.charAt(i))
                    {
                        case ',':
                            if (!found.apply(token))
                                return false;
                            next = i + 1;
                            continue parsing;
                        case ' ':
                            break;
                        default:
                            break loop;
                    }
                    i++;
                }
            }

            // Token was not correctly matched
            if (' ' == value.charAt(next))
            {
                next++;
                continue;
            }

            int comma = value.indexOf(',', next);
            if (comma == next)
            {
                next++;
                continue;
            }
            else if (comma > next)
            {
                if (unknown == null)
                {
                    next = comma + 1;
                    continue;
                }
                String v = value.substring(next, comma).trim();
                if (StringUtil.isBlank(v) || unknown.apply(v))
                {
                    next = comma + 1;
                    continue;
                }
            }
            return false;
        }
        return true;
    }
}
