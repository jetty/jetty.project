//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.util.List;

public class HeaderUtil
{
    /**
     * ABNF from RFC 2616, RFC 822, and RFC 6455 specified characters requiring quoting.
     */
    public static final String ABNF_REQUIRED_QUOTING = "\"'\\\n\r\t\f\b%+ ;=";

    public static String joinValues(List<String> values)
    {
        // no value list
        if (values == null)
        {
            return null;
        }

        int size = values.size();
        // empty value list
        if (size <= 0)
        {
            return null;
        }
        // simple return
        if (size == 1)
        {
            return values.get(0);
        }
        // join it with commas
        boolean needsDelim = false;
        StringBuilder ret = new StringBuilder();
        for (String value : values)
        {
            if (needsDelim)
            {
                ret.append(", ");
            }
            quoteIfNeeded(ret, value, ABNF_REQUIRED_QUOTING);
            needsDelim = true;
        }
        return ret.toString();
    }

    /**
     * Append into buf the provided string, adding quotes if needed.
     * <p>
     * Quoting is determined if any of the characters in the {@code delim} are found in the input {@code str}.
     *
     * @param buf the buffer to append to
     * @param str the string to possibly quote
     * @param delim the delimiter characters that will trigger automatic quoting
     */
    private static void quoteIfNeeded(StringBuilder buf, String str, String delim)
    {
        if (str == null)
        {
            return;
        }
        // check for delimiters in input string
        int len = str.length();
        if (len == 0)
        {
            return;
        }
        int ch;
        for (int i = 0; i < len; i++)
        {
            ch = str.codePointAt(i);
            if (delim.indexOf(ch) >= 0)
            {
                // found a delimiter codepoint. we need to quote it.
                buf.append('"');
                buf.append(str);
                buf.append('"');
                return;
            }
        }

        // no special delimiters used, no quote needed.
        buf.append(str);
    }
}
