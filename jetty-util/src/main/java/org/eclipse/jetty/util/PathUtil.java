//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Collection of Path encoding / decoding utilities for URI, Http Headers, and HTML
 */
public class PathUtil
{
    // Simple hex array
    private static final char[] HEXES = "0123456789ABCDEF".toCharArray();
    // The first 127 characters and their encodings.
    private static final String[] URI_ENCODED_CHARS;

    static
    {
        URI_ENCODED_CHARS = new String[127];
        Arrays.fill(URI_ENCODED_CHARS, null);

        // URI Reserved Chars
        String uriReservedGenDelims = ":?#[]@"; // intentionally missing "/"
        String uriReservedSubDelims = "!$&'()*+,;=";
        // Extra Reserved Chars (specified by Jetty)
        String jettyReserved = "%\"<> \\^`{}|";

        String reserved = uriReservedGenDelims + uriReservedSubDelims + jettyReserved;

        for (int i = 0; i < URI_ENCODED_CHARS.length; i++)
        {
            if ((i < 0x20) || // control characters
                (reserved.indexOf(i) != (-1)))
            {
                // encoding needed
                URI_ENCODED_CHARS[i] = String.format("%%%02X", i);
            }
        }
    }

    public static String encodePath(String rawpath)
    {
        byte[] rawpathbuf = rawpath.getBytes(StandardCharsets.UTF_8);
        int len = rawpathbuf.length;
        StringBuilder buf = new StringBuilder(len * 2);
        for (byte c : rawpathbuf)
        {
            if ((c >= 0) && c <= URI_ENCODED_CHARS.length)
            {
                // this is the 7-bit US-ASCII space
                // only some of these characters are encoded
                String e = URI_ENCODED_CHARS[c];
                if (e != null)
                    buf.append(e);
                else
                    buf.append((char)c);
            }
            else
            {
                // all of these characters are 8-bit and above (unicode) and should be encoded.
                buf.append('%');
                buf.append(HEXES[(c & 0xF0) >> 4]);
                buf.append(HEXES[(c & 0x0F)]);
            }
        }
        return buf.toString();
    }

    public static String encodePathDelayAlloc(String rawpath)
    {
        int len = rawpath.length();
        StringBuilder buf = null;
        byte[] bytes = null;
        for (int i = 0; i < len; i++)
        {
            char c = rawpath.charAt(i);
            if (c <= URI_ENCODED_CHARS.length)
            {
                // this is the 7-bit US-ASCII space
                // only some of these characters are encoded
                String e = URI_ENCODED_CHARS[c];
                if (e != null)
                {
                    // we have a character that needs encoding
                    if (buf == null)
                    {
                        buf = new StringBuilder(len * 2);
                        buf.append(rawpath, 0, i);
                    }
                    buf.append(URI_ENCODED_CHARS[c]);
                }
                else if (buf != null)
                {
                    // append raw character
                    buf.append(c);
                }
            }
            else
            {
                // we encountered a character that is 8-bit, which means we have unicode style encoding requirement.

                // remember where we left off in the scan of the input raw path.
                if (buf == null)
                {
                    buf = new StringBuilder(len * 2);
                    buf.append(rawpath, 0, i);
                }

                // convert remainder to encoded byte array
                bytes = rawpath.substring(i).getBytes(StandardCharsets.UTF_8);
                // and finish processing as byte array now
                i = len;
            }
        }

        // we need to process the rest as a byte array.
        if (bytes != null)
        {
            for (byte c : bytes)
            {
                if ((c >= 0) && c <= URI_ENCODED_CHARS.length)
                {
                    // this is the 7-bit US-ASCII space
                    // only some of these characters are encoded
                    String e = URI_ENCODED_CHARS[c];
                    if (e != null)
                        buf.append(e);
                    else
                        buf.append((char)c);
                }
                else
                {
                    // all of these characters are 8-bit and above (unicode) and should be encoded.
                    buf.append('%');
                    buf.append(HEXES[(c & 0xF0) >> 4]);
                    buf.append(HEXES[(c & 0x0F)]);
                }
            }
        }

        if (buf != null)
            return buf.toString();
        return rawpath;
    }
}
