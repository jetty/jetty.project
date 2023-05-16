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

import java.util.Iterator;
import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class QuotedStringTokenizerTest
{
    public static Stream<Arguments> tokenizerTests()
    {
        QuotedStringTokenizer commaList = QuotedStringTokenizer.builder().delimiters(",").build();
        QuotedStringTokenizer commaListOws = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().build();
        QuotedStringTokenizer commaListOwsEmbedded = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().embeddedQuotes().build();
        QuotedStringTokenizer commaListDelimiters = QuotedStringTokenizer.builder().delimiters(",").returnDelimiters().build();
        QuotedStringTokenizer commaListOwsDelimiters = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().returnDelimiters().build();
        QuotedStringTokenizer commaListOwsEmbeddedQuotes = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().returnQuotes().embeddedQuotes().build();
        QuotedStringTokenizer commaListEscapeOQ = QuotedStringTokenizer.builder().delimiters(",").escapeOnlyQuote().build();

        return Stream.of(
            Arguments.of(commaList, "", new String[] {}),
            Arguments.of(commaList, "a,b,c", new String[] {"a", "b", "c"}),
            Arguments.of(commaList, " a ,  b  ,   c   ", new String[] {" a ", "  b  ", "   c   "}),
            Arguments.of(commaList, "a a,b  b, c c ", new String[] {"a a", "b  b", " c c "}),
            Arguments.of(commaList, "\"a,a\",\"b,b\",c", new String[] {"a,a", "b,b", "c"}),
            Arguments.of(commaList, "\"a,a\", b\",\"b ,c", new String[] {"a,a", " b\"", null}),
            Arguments.of(commaList, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", "b\\b", "c,c"}),

            Arguments.of(commaListOws, "", new String[] {}),
            Arguments.of(commaListOws, "a,b,c", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOws, " a ,  b  ,   c   ", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOws, "a a,b  b, c c ", new String[] {"a a", "b  b", "c c"}),
            Arguments.of(commaListOws, "\"a,a\",\"b,b\",c", new String[] {"a,a", "b,b", "c"}),
            Arguments.of(commaListOws, "\"a,a\", b\",\"b ,c", new String[] {"a,a", "b\"", null}),
            Arguments.of(commaListOws, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", "b\\b", "c,c"}),

            Arguments.of(commaListOwsEmbedded, "", new String[] {}),
            Arguments.of(commaListOwsEmbedded, "a,b,c", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOwsEmbedded, " a ,  b  ,   c   ", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOwsEmbedded, "a a,b  b, c c ", new String[] {"a a", "b  b", "c c"}),
            Arguments.of(commaListOwsEmbedded, "\"a,a\",\"b,b\",c", new String[] {"a,a", "b,b", "c"}),
            Arguments.of(commaListOwsEmbedded, "\"a,a\", b\",\"b ,c", new String[] {"a,a", "b,b", "c"}),
            Arguments.of(commaListOwsEmbedded, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", "b\\b", "c,c"}),

            Arguments.of(commaListDelimiters, "", new String[] {}),
            Arguments.of(commaListDelimiters, "a,b,c", new String[] {"a", ",", "b", ",", "c"}),
            Arguments.of(commaListDelimiters, " a ,  b  ,   c   ", new String[] {" a ", ",", "  b  ", ",", "   c   "}),
            Arguments.of(commaListDelimiters, "a a,b  b, c c ", new String[] {"a a", ",", "b  b", ",", " c c "}),
            Arguments.of(commaListDelimiters, "\"a,a\",\"b,b\",c", new String[] {"a,a", ",", "b,b", ",", "c"}),
            Arguments.of(commaListDelimiters, "\"a,a\", b\",\"b ,c", new String[] {"a,a", ",", " b\"", ",", null}),
            Arguments.of(commaListDelimiters, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", ",", "b\\b", ",", "c,c"}),

            Arguments.of(commaListOwsDelimiters, "", new String[] {}),
            Arguments.of(commaListOwsDelimiters, "a,b,c", new String[] {"a", ",", "b", ",", "c"}),
            Arguments.of(commaListOwsDelimiters, " a ,  b  ,   c   ", new String[] {"a", ",", "b", ",", "c"}),
            Arguments.of(commaListOwsDelimiters, "a a,b  b, c c ", new String[] {"a a", ",", "b  b", ",", "c c"}),
            Arguments.of(commaListOwsDelimiters, "\"a,a\",\"b,b\",c", new String[] {"a,a", ",", "b,b", ",", "c"}),
            Arguments.of(commaListOwsDelimiters, "\"a,a\", b\",\"b ,c", new String[] {"a,a", ",", "b\"", ",", null}),
            Arguments.of(commaListOwsDelimiters, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", ",", "b\\b", ",", "c,c"}),

            Arguments.of(commaListOwsEmbeddedQuotes, "", new String[] {}),
            Arguments.of(commaListOwsEmbeddedQuotes, "a,b,c", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOwsEmbeddedQuotes, " a ,  b  ,   c   ", new String[] {"a", "b", "c"}),
            Arguments.of(commaListOwsEmbeddedQuotes, "a a,b  b, c c ", new String[] {"a a", "b  b", "c c"}),
            Arguments.of(commaListOwsEmbeddedQuotes, "\"a,a\",\"b,b\",c", new String[] {"\"a,a\"", "\"b,b\"", "c"}),
            Arguments.of(commaListOwsEmbeddedQuotes, "\"a,a\", b\",\"b ,c", new String[] {"\"a,a\"", "b\",\"b", "c"}),
            Arguments.of(commaListOwsEmbeddedQuotes, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"\"a\\\"a\"", "\"b\\\\b\"", "\"c\\,c\""}),

            Arguments.of(commaListEscapeOQ, "", new String[] {}),
            Arguments.of(commaListEscapeOQ, "a,b,c", new String[] {"a", "b", "c"}),
            Arguments.of(commaListEscapeOQ, " a ,  b  ,   c   ", new String[] {" a ", "  b  ", "   c   "}),
            Arguments.of(commaListEscapeOQ, "a a,b  b, c c ", new String[] {"a a", "b  b", " c c "}),
            Arguments.of(commaListEscapeOQ, "\"a,a\",\"b,b\",c", new String[] {"a,a", "b,b", "c"}),
            Arguments.of(commaListEscapeOQ, "\"a,a\", b\",\"b ,c", new String[] {"a,a", " b\"", null}),
            Arguments.of(commaListEscapeOQ, "\"a\\\"a\",\"b\\\\b\",\"c\\,c\"", new String[] {"a\"a", "b\\\\b", "c\\,c"}),

            Arguments.of(commaList, null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("tokenizerTests")
    public void testTokenizer(QuotedStringTokenizer tokenizer, String string, String[] expected)
    {
        if (expected == null)
        {
            assertThrows(NullPointerException.class, () -> tokenizer.tokenize(string));
            return;
        }
        Iterator<String> iterator = tokenizer.tokenize(string);
        int i = 0;
        while (i < expected.length)
        {
            String token = expected[i++];
            if (token == null)
                assertThrows(IllegalArgumentException.class, iterator::hasNext);
            else
            {
                assertTrue(iterator.hasNext());
                assertThat(iterator.next(), Matchers.equalTo(token));
            }
        }
    }

    @Test
    public void testQuote()
    {
        StringBuffer buf = new StringBuffer();

        buf.setLength(0);
        QuotedStringTokenizer.CSV.quote(buf, "abc \n efg");
        assertEquals("\"abc \n efg\"", buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.CSV.quote(buf, "abcefg");
        assertEquals("\"abcefg\"", buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.CSV.quote(buf, "abcefg\"");
        assertEquals("\"abcefg\\\"\"", buf.toString());
    }

    /*
     * Test for String quote(String, String)
     */
    @Test
    public void testQuoteIfNeeded()
    {
        QuotedStringTokenizer tokenizer = QuotedStringTokenizer.CSV; // OWS
        assertEquals("abc", tokenizer.quoteIfNeeded("abc"));
        assertEquals("\"a c\"", tokenizer.quoteIfNeeded("a c"));
        assertEquals("a c", QuotedStringTokenizer.builder().delimiters(",").build().quoteIfNeeded("a c")); // No OWS
        assertEquals("a'c", tokenizer.quoteIfNeeded("a'c"));
        assertEquals("\"a\\\"c\"", tokenizer.quoteIfNeeded("a\"c"));
        assertEquals("\"a\n\r\t\"", tokenizer.quoteIfNeeded("a\n\r\t"));
        assertEquals("\"\u0000\u001f\"", tokenizer.quoteIfNeeded("\u0000\u001f"));
        assertEquals("\"a\\\"c\"", tokenizer.quoteIfNeeded("a\"c"));
    }

    @Test
    public void testUnquote()
    {
        assertEquals("abc", QuotedStringTokenizer.CSV.unquote("abc"));
        assertEquals("a\"c", QuotedStringTokenizer.CSV.unquote("\"a\\\"c\""));
        assertEquals("a'c", QuotedStringTokenizer.CSV.unquote("\"a'c\""));
        assertEquals("anrt", QuotedStringTokenizer.CSV.unquote("\"a\\n\\r\\t\""));
        assertEquals("\u0000\u001f ", QuotedStringTokenizer.CSV.unquote("\"\u0000\u001f \""));
        assertEquals("\u0000\u001f ", QuotedStringTokenizer.CSV.unquote("\"\u0000\u001f \""));
        assertEquals("ab\u001ec", QuotedStringTokenizer.CSV.unquote("ab\u001ec"));
        assertEquals("ab\u001ec", QuotedStringTokenizer.CSV.unquote("\"ab\u001ec\""));
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
        String contentDisposition = "form-data; name=\"fileup\"; filename=\"C:\\Pictures\\20120504.jpg\"";

        QuotedStringTokenizer tok = QuotedStringTokenizer.builder().delimiters(";").optionalWhiteSpace().returnQuotes().embeddedQuotes().escapeOnlyQuote().build();
        Iterator<String> iter = tok.tokenize(contentDisposition);

        assertEquals("form-data", iter.next());
        assertEquals("name=\"fileup\"", iter.next());
        assertEquals("filename=\"C:\\Pictures\\20120504.jpg\"", iter.next());
    }
}
