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

package org.eclipse.jetty.util;

import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class QuotedStringTokenizerTest
{
    public static Stream<Arguments> tokenizerTests()
    {
        return Stream.of(
            Arguments.of("", ", ", false, false, new String[] {}),
            Arguments.of("a,b,c", ",", false, false, new String[] {"a", "b", "c"}),
            Arguments.of("a,b, c", ",", false, false, new String[] {"a", "b", " c"}),
            Arguments.of("a,b, c", " ,", false, false, new String[] {"a", "b", "c"}),
            Arguments.of("a,b, c", " ,", false, false, new String[] {"a", "b", "c"}),
            Arguments.of("a,b,c", ",", true, false, new String[] {"a", ",", "b", ",", "c"}),
            Arguments.of("a, b, c", ", ", true, false, new String[] {"a", ",", " ", "b", ",", " ", "c"}),

            Arguments.of("a,\"b\",c", ",", false, false, new String[] {"a", "b", "c"}),
            Arguments.of("a,\"b,c\"", ",", false, false, new String[] {"a", "b,c"}),
            Arguments.of("a,\"b\",c", ",", false, true, new String[] {"a", "\"b\"", "c"}),
            Arguments.of("a,\"b,c\"", ",", false, true, new String[] {"a", "\"b,c\""}),

            Arguments.of("a,\"\\\"b\\\"\",c", ",", false, false, new String[] {"a", "\"b\"", "c"}),

            Arguments.of("a,\"b,c", ",", false, true, new String[] {"a", "\"b,c"}),

            Arguments.of("", ", ", false, false, new String[] {})
        );
    }

    @ParameterizedTest
    @MethodSource("tokenizerTests")
    public void testTokenizer(String string, String delims, boolean delim, boolean quotes, String[] expected)
    {
        QuotedStringTokenizer tokenizer = new QuotedStringTokenizer(string, delims, delim, quotes);

        if (expected == null)
            assertFalse(tokenizer.hasMoreTokens());
        else
        {
            int i = 0;
            while (i < expected.length)
            {
                assertTrue(tokenizer.hasMoreTokens());
                assertThat(tokenizer.nextToken(), Matchers.equalTo(expected[i++]));
            }
        }
    }

    @Test
    public void testQuote()
    {
        StringBuffer buf = new StringBuffer();

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf, "abc \n efg");
        assertEquals("\"abc \n efg\"", buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf, "abcefg");
        assertEquals("\"abcefg\"", buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf, "abcefg\"");
        assertEquals("\"abcefg\\\"\"", buf.toString());
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer4()
    {
        QuotedStringTokenizer tok = new QuotedStringTokenizer("abc'def,ghi'jkl", ",");
        assertEquals("abc'def", tok.nextToken());
        assertEquals("ghi'jkl", tok.nextToken());
    }

    /*
     * Test for String quote(String, String)
     */
    @Test
    public void testQuoteIfNeeded()
    {
        assertEquals("abc", QuotedStringTokenizer.quoteIfNeeded("abc", " ,"));
        assertEquals("\"a c\"", QuotedStringTokenizer.quoteIfNeeded("a c", " ,"));
        assertEquals("a'c", QuotedStringTokenizer.quoteIfNeeded("a'c", " ,"));
        assertEquals("\"a\\\"c\"", QuotedStringTokenizer.quoteIfNeeded("a\"c", " ,"));
        assertEquals("a\n\r\t", QuotedStringTokenizer.quoteIfNeeded("a\n\r\t", " ,"));
        assertEquals("\u0000\u001f", QuotedStringTokenizer.quoteIfNeeded("\u0000\u001f", ", "));
        assertEquals("\"a\\\"c\"", QuotedStringTokenizer.quoteIfNeeded("a\"c", ", "));
    }

    @Test
    public void testUnquote()
    {
        assertEquals("abc", QuotedStringTokenizer.unquote("abc"));
        assertEquals("a\"c", QuotedStringTokenizer.unquote("\"a\\\"c\""));
        assertEquals("a'c", QuotedStringTokenizer.unquote("\"a'c\""));
        assertEquals("anrt", QuotedStringTokenizer.unquote("\"a\\n\\r\\t\""));
        assertEquals("\u0000\u001f ", QuotedStringTokenizer.unquote("\"\u0000\u001f \""));
        assertEquals("\u0000\u001f ", QuotedStringTokenizer.unquote("\"\u0000\u001f \""));
        assertEquals("ab\u001ec", QuotedStringTokenizer.unquote("ab\u001ec"));
        assertEquals("ab\u001ec", QuotedStringTokenizer.unquote("\"ab\u001ec\""));
    }

    @Test
    public void testUnquoteOnly()
    {
        assertEquals("abc", QuotedStringTokenizer.unquoteOnly("abc"));
        assertEquals("a\"c", QuotedStringTokenizer.unquoteOnly("\"a\\\"c\""));
        assertEquals("a'c", QuotedStringTokenizer.unquoteOnly("\"a'c\""));
        assertEquals("a\\n\\r\\t", QuotedStringTokenizer.unquoteOnly("\"a\\\\n\\\\r\\\\t\""));
        assertEquals("ba\\uXXXXaaa", QuotedStringTokenizer.unquoteOnly("\"ba\\\\uXXXXaaa\""));
    }

    /**
     * When encountering a Content-Disposition line during a multi-part mime file
     * upload, the filename="..." field can contain '\' characters that do not
     * belong to a proper escaping sequence, this tests QuotedStringTokenizer to
     * ensure that it preserves those slashes for where they cannot be escaped.
     */
    @Test
    public void testNextTokenOnContentDisposition()
    {
        String contentDisposition = "form-data; name=\"fileup\"; filename=\"Taken on Aug 22 \\ 2012.jpg\"";

        QuotedStringTokenizer tok = new QuotedStringTokenizer(contentDisposition, ";", false, true);

        assertEquals("form-data", tok.nextToken().trim());
        assertEquals("name=\"fileup\"", tok.nextToken().trim());
        assertEquals("filename=\"Taken on Aug 22 \\ 2012.jpg\"", tok.nextToken().trim());
    }
}
