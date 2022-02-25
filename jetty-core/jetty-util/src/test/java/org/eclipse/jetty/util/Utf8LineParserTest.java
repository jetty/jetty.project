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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class Utf8LineParserTest
{
    private void appendUtf8(ByteBuffer buf, String line)
    {
        buf.put(ByteBuffer.wrap(StringUtil.getUtf8Bytes(line)));
    }

    private void assertEquals(List<String> expected, List<String> actual)
    {
        assertThat("Expected Line Count", actual.size(), is(expected.size()));
        int len = expected.size();
        for (int i = 0; i < len; i++)
        {
            String expectedLine = expected.get(i);
            String actualLine = actual.get(i);

            assertThat("Line[" + i + "]", actualLine, is(expectedLine));
        }
    }

    /**
     * Parse a basic line, with UNIX style line endings <code>"\n"</code>
     */
    @Test
    public void testBasicParse()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        appendUtf8(buf, "Hello World\n");
        BufferUtil.flipToFlush(buf, 0);

        Utf8LineParser utfparser = new Utf8LineParser();

        String line = utfparser.parse(buf);
        assertThat("Line", line, is("Hello World"));
    }

    /**
     * Parsing of a single line of HTTP header style line ending <code>"\r\n"</code>
     */
    @Test
    public void testHttpLineParse()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        appendUtf8(buf, "Hello World\r\n");
        BufferUtil.flipToFlush(buf, 0);

        Utf8LineParser utfparser = new Utf8LineParser();

        String line = utfparser.parse(buf);
        assertThat("Line", line, is("Hello World"));
    }

    /**
     * Parsing of an "in the wild" set HTTP response header lines.
     */
    @Test
    public void testWildHttpRequestParse()
    {
        // Arbitrary Http Response Headers seen in the wild.
        // Request URI -> http://www.eclipse.org/jetty/
        List<String> expected = new ArrayList<>();
        expected.add("HEAD /jetty/ HTTP/1.0");
        expected.add("User-Agent: \"Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.8.1.6) Gecko/20060601 Firefox/2.0.0.6 (Ubuntu-feisty)\"");
        expected.add("Accept: */*");
        expected.add("Host: www.eclipse.org");
        expected.add("Connection: Keep-Alive");
        expected.add("");

        // Prepare Buffer
        ByteBuffer buf = ByteBuffer.allocate(512);
        for (String line : expected)
        {
            appendUtf8(buf, line + "\r\n");
        }

        BufferUtil.flipToFlush(buf, 0);

        // Parse Buffer
        Utf8LineParser utfparser = new Utf8LineParser();

        List<String> actual = new ArrayList<>();
        int count = 0;
        int excessive = expected.size() + 10; // fail-safe for bad code
        boolean done = false;
        while (!done)
        {
            String line = utfparser.parse(buf);
            if (line != null)
            {
                actual.add(line);
            }
            else
            {
                done = true;
            }
            count++;
            assertThat("Parse Count is excessive (bug in code!)", count, lessThan(excessive));
        }

        // Validate Results
        assertEquals(expected, actual);
    }

    /**
     * Parsing of an "in the wild" set HTTP response header lines.
     */
    @Test
    public void testWildHttpResponseParse()
    {
        // Arbitrary Http Response Headers seen in the wild.
        // Request URI -> https://ssl.google-analytics.com/__utm.gif
        List<String> expected = new ArrayList<>();
        expected.add("HTTP/1.0 200 OK");
        expected.add("Date: Thu, 09 Aug 2012 16:16:39 GMT");
        expected.add("Content-Length: 35");
        expected.add("X-Content-Type-Options: nosniff");
        expected.add("Pragma: no-cache");
        expected.add("Expires: Wed, 19 Apr 2000 11:43:00 GMT");
        expected.add("Last-Modified: Wed, 21 Jan 2004 19:51:30 GMT");
        expected.add("Content-Type: image/gif");
        expected.add("Cache-Control: private, no-cache, no-cache=Set-Cookie, proxy-revalidate");
        expected.add("Age: 518097");
        expected.add("Server: GFE/2.0");
        expected.add("Connection: Keep-Alive");
        expected.add("");

        // Prepare Buffer
        ByteBuffer buf = ByteBuffer.allocate(512);
        for (String line : expected)
        {
            appendUtf8(buf, line + "\r\n");
        }

        BufferUtil.flipToFlush(buf, 0);

        // Parse Buffer
        Utf8LineParser utfparser = new Utf8LineParser();

        List<String> actual = new ArrayList<>();
        int count = 0;
        int excessive = expected.size() + 10; // fail-safe for bad code
        boolean done = false;
        while (!done)
        {
            String line = utfparser.parse(buf);
            if (line != null)
            {
                actual.add(line);
            }
            else
            {
                done = true;
            }
            count++;
            assertThat("Parse Count is excessive (bug in code!)", count, lessThan(excessive));
        }

        // Validate Results
        assertEquals(expected, actual);
    }
}
