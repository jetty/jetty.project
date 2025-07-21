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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class UrlParameterDecoderTest
{
    @Test
    public void testCharSequenceCharIterator() throws IOException
    {
        UrlParameterDecoder.CharIterator iter = new UrlParameterDecoder.CharSequenceCharIterator("Hello");
        StringBuilder output = new StringBuilder();

        int i;
        while ((i = iter.next()) >= 0)
        {
            output.append((char)i);
        }
        assertEquals("Hello", output.toString());
    }

    @Test
    public void testStringCharIterator() throws IOException
    {
        UrlParameterDecoder.CharIterator iter = new UrlParameterDecoder.StringCharIterator("Hello");
        StringBuilder output = new StringBuilder();

        int i;
        while ((i = iter.next()) >= 0)
        {
            output.append((char)i);
        }
        assertEquals("Hello", output.toString());
    }

    @Test
    public void testReaderCharIterator() throws IOException
    {
        Reader reader = new StringReader("Hello");
        UrlParameterDecoder.CharIterator iter = new UrlParameterDecoder.ReaderCharIterator(reader);
        StringBuilder output = new StringBuilder();

        int i;
        while ((i = iter.next()) >= 0)
        {
            output.append((char)i);
        }
        assertEquals("Hello", output.toString());
    }

    @Test
    public void testBasic()
        throws Exception
    {
        Fields fields = new Fields();

        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String input = "a=b&c=d";
        assertFalse(decoder.parse(input), "No coding errors");

        assertEquals("b", fields.getValue("a"));
        assertEquals("d", fields.getValue("c"));
    }

    /**
     * List of parsing behaviors from Browser {@code URLSearchParams} implementations.
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/URLSearchParams">URLSearchParams</a>
     */
    public static Stream<Arguments> browserParsingCases()
    {
        List<Arguments> cases = new ArrayList<>();

        cases.add(Arguments.of("a=b&c=d", Map.of("a", List.of("b"), "c", List.of("d"))));
        cases.add(Arguments.of("a=b?c=d", Map.of("a", List.of("b?c=d"))));
        cases.add(Arguments.of("=", Map.of("", List.of(""))));
        cases.add(Arguments.of("a=b&a=c&a=d", Map.of("a", List.of("b", "c", "d"))));
        cases.add(Arguments.of("&", Map.of()));
        cases.add(Arguments.of("&&&", Map.of()));
        cases.add(Arguments.of("a=b&&&", Map.of("a", List.of("b"))));
        cases.add(Arguments.of("&&&a=b", Map.of("a", List.of("b"))));
        cases.add(Arguments.of("=&=", Map.of("", List.of("", ""))));
        cases.add(Arguments.of("&=&", Map.of("", List.of(""))));
        cases.add(Arguments.of("foo", Map.of("foo", List.of(""))));
        cases.add(Arguments.of("foo&bar", Map.of("foo", List.of(""), "bar", List.of(""))));
        cases.add(Arguments.of("foo=", Map.of("foo", List.of(""))));
        cases.add(Arguments.of("foo=&", Map.of("foo", List.of(""))));
        cases.add(Arguments.of("=foo", Map.of("", List.of("foo"))));
        cases.add(Arguments.of("=foo&=bar", Map.of("", List.of("foo", "bar"))));
        cases.add(Arguments.of("foo==", Map.of("foo", List.of("="))));
        cases.add(Arguments.of("foo===", Map.of("foo", List.of("=="))));
        cases.add(Arguments.of("a===b", Map.of("a", List.of("==b"))));
        cases.add(Arguments.of("a=\"b\"", Map.of("a", List.of("\"b\""))));
        cases.add(Arguments.of("\"a=b\"", Map.of("\"a", List.of("b\""))));
        cases.add(Arguments.of("a=b& =foo", Map.of("a", List.of("b"), " ", List.of("foo"))));
        cases.add(Arguments.of("===foo", Map.of("", List.of("==foo"))));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("browserParsingCases")
    public void testBrowserParsingBehavior(String input, Map<String, List<String>> expectedParams) throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        assertFalse(decoder.parse(input), "No coding errors");

        assertThat("Field count", fields.getSize(), is(expectedParams.size()));

        for (String key : expectedParams.keySet())
        {
            Fields.Field field = fields.get(key);
            String message = "Fields[%s]".formatted(key);
            assertNotNull(field, message);
            assertEquals(expectedParams.get(key), field.getValues(), message);
        }
    }

    @Test
    public void testManyPctEncoding()
        throws Exception
    {
        Fields fields = new Fields();

        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String input = "text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit";
        assertFalse(decoder.parse(input), "No coding errors");

        String hex = "E0B89FE0B8ABE0B881E0B8A7E0B894E0B8B2E0B988E0B881E0B89FE0B8A7E0B8ABE0B8AAE0B894E0B8B2E0B988E0B8ABE0B89FE0B881E0B8A7E0B894E0B8AAE0B8B2E0B89FE0B881E0B8ABE0B8A3E0B894E0B989E0B89FE0B8ABE0B899E0B881E0B8A3E0B894E0B8B5";
        String expected = new String(StringUtil.fromHexString(hex), UTF_8);
        assertEquals(expected, fields.getValue("text"));
    }

    @Test
    public void testUtf8MultiByteCodePoint() throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String input = "text=test%C3%A4";
        assertFalse(decoder.parse(input), "No coding errors");

        // http://www.ltg.ed.ac.uk/~richard/utf-8.cgi?input=00e4&mode=hex
        // Should be "testä"
        // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS

        String expected = "testä";
        assertThat(fields.getValue("text"), is(expected));
    }

    public static Stream<Arguments> invalidTestData()
    {
        List<Arguments> cases = new ArrayList<>();

        List<Charset> charsets = List.of(UTF_8, ISO_8859_1, US_ASCII, Charset.forName("Shift-JIS"));
        // First test fundamentally bad pct-encoding issues against several charsets
        // It shouldn't matter what the charset is here, as the issue happens before
        // the charset is even involved.
        for (Charset charset : charsets)
        {
            cases.add(Arguments.of(charset, "Name=xx%zzyy", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "Name=%E%F%F", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "Name=x%", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "Name=x%2", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "Name=xxx%", IllegalArgumentException.class));
        }

        // Complete pct-encoding sequences that some charsets do not like
        for (Charset charset : List.of(UTF_8, US_ASCII))
        {
            cases.add(Arguments.of(charset, "Name=%FF%FF%FF", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "Name=%EF%EF%EF", IllegalArgumentException.class));
            cases.add(Arguments.of(charset, "name=X%c0%afZ", IllegalArgumentException.class));
        }

        // Next add specific cases for specific charsets.
        // Euro unicode not allowed in US_ASCII
        cases.add(Arguments.of(US_ASCII, "Name=euro%E2%82%AC", IllegalArgumentException.class));
        // Byte 0x80 unicode not allowed in US_ASCII
        cases.add(Arguments.of(US_ASCII, "Name=%80x", IllegalArgumentException.class));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidTestData")
    public <X extends Throwable> void testInvalidDecode(Charset charset, String input, Class<X> expectedThrowableType)
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(charset);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        assertThrows(expectedThrowableType, () -> decoder.parse(input));
    }

    /**
     * Example of a parameter with raw UTF-8 unicode which isn't pct-encoded.
     */
    @Test
    public void testBadlyEncodedValue() throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = new Utf8StringBuilder();
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "Name=Euro-€-Symbol";
        assertFalse(decoder.parse(input), "No coding errors");
        assertThat("Field count", fields.getSize(), is(1));
        Fields.Field field = fields.get("Name");
        assertNotNull(field, "Fields[Name]");
        assertEquals("Euro-€-Symbol", field.getValue(), "Fields[Name]");
    }

    @Test
    public void testUtf16EncodedString() throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_16);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "name\n=value+%FE%FF%00%30&name1=&name2&nãme3=value+3";
        assertFalse(decoder.parse(input), "No coding errors");

        assertThat("Field count", fields.getSize(), is(4));
        Fields.Field field = fields.get("name\n");
        assertNotNull(field, "Fields[name\\n]");
        assertEquals("value 0", field.getValue(), "Fields[name\\n]");

        field = fields.get("name1");
        assertNotNull(field, "Fields[name1]");
        assertEquals("", field.getValue(), "Fields[name1]");

        field = fields.get("name2");
        assertNotNull(field, "Fields[name2]");
        assertEquals("", field.getValue(), "Fields[name2]");

        field = fields.get("nãme3");
        assertNotNull(field, "Fields[nãme3]");
        assertEquals("value 3", field.getValue(), "Fields[nãme3]");
    }

    public static Stream<Arguments> queryBehaviorsBadUtf8AllowedGood()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal cases
        cases.add(Arguments.of("param=aaa", Map.of("param", "aaa")));
        cases.add(Arguments.of("param=aaa&other=foo", Map.of("param", "aaa", "other", "foo")));
        cases.add(Arguments.of("param=", Map.of("param", "")));
        cases.add(Arguments.of("param=&other=foo", Map.of("param", "", "other", "foo")));
        cases.add(Arguments.of("param=%E2%9C%94", Map.of("param", "✔")));
        cases.add(Arguments.of("param=%E2%9C%94&other=foo", Map.of("param", "✔", "other", "foo")));

        // Extra ampersands
        cases.add(Arguments.of("param=aaa&&&", Map.of("param", "aaa")));
        cases.add(Arguments.of("&&&param=aaa", Map.of("param", "aaa")));
        cases.add(Arguments.of("&&param=aaa&&other=foo", Map.of("param", "aaa", "other", "foo")));
        cases.add(Arguments.of("param=aaa&&other=foo&&", Map.of("param", "aaa", "other", "foo")));

        // Encoded ampersands
        cases.add(Arguments.of("param=aaa%26&other=foo", Map.of("param", "aaa&", "other", "foo")));
        cases.add(Arguments.of("param=aaa&%26other=foo", Map.of("param", "aaa", "&other", "foo")));

        // pct-encoded parameter names ("帽子" means "hat" in japanese)
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret", Map.of("帽子", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret&other=foo", Map.of("帽子", "Beret", "other", "foo")));
        cases.add(Arguments.of("other=foo&%E5%B8%BD%E5%AD%90=Beret", Map.of("帽子", "Beret", "other", "foo")));

        return cases.stream();
    }

    public static Stream<Arguments> queryBehaviorsBadUtf8AllowedBad()
    {
        List<Arguments> cases = new ArrayList<>();

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C%9", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%9C%9&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2%9C%", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%9C%&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2%9C", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%9C&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2%9", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%9&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2%", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2&other=foo", Map.of("param", "�", "other", "foo")));

        // Tokenized cases
        cases.add(Arguments.of("param=%%TOK%%", Map.of("param", "%%TOK%%")));
        cases.add(Arguments.of("param=%%TOK%%&other=foo", Map.of("param", "%%TOK%%", "other", "foo")));

        // Bad Hex
        cases.add(Arguments.of("param=%xx", Map.of("param", "%xx")));
        cases.add(Arguments.of("param=%xx&other=foo", Map.of("param", "%xx", "other", "foo")));

        // Overlong UTF-8 Encoding
        cases.add(Arguments.of("param=%C0%AF", Map.of("param", "��")));
        cases.add(Arguments.of("param=%C0%AF&other=foo", Map.of("param", "��", "other", "foo")));

        // Out of range
        cases.add(Arguments.of("param=%F4%90%80%80", Map.of("param", "����")));
        cases.add(Arguments.of("param=%F4%90%80%80&other=foo", Map.of("param", "����", "other", "foo")));

        // Long surrogate
        cases.add(Arguments.of("param=%ED%A0%80", Map.of("param", "���")));
        cases.add(Arguments.of("param=%ED%A0%80&other=foo", Map.of("param", "���", "other", "foo")));

        // Standalone continuations
        cases.add(Arguments.of("param=%80", Map.of("param", "�")));
        cases.add(Arguments.of("param=%80&other=foo", Map.of("param", "�", "other", "foo")));

        // Truncated sequence
        cases.add(Arguments.of("param=%E2%82", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%82&other=foo", Map.of("param", "�", "other", "foo")));

        // C1 never starts UTF-8
        cases.add(Arguments.of("param=%C1%BF", Map.of("param", "��")));
        cases.add(Arguments.of("param=%C1%BF&other=foo", Map.of("param", "��", "other", "foo")));

        // E0 must be followed by A0-BF
        cases.add(Arguments.of("param=%E0%9F%80", Map.of("param", "���")));
        cases.add(Arguments.of("param=%E0%9F%80&other=foo", Map.of("param", "���", "other", "foo")));

        // Community Examples
        cases.add(Arguments.of("param=f_%e0%b8", Map.of("param", "f_�")));
        cases.add(Arguments.of("param=f_%e0%b8&other=foo", Map.of("param", "f_�", "other", "foo")));
        cases.add(Arguments.of("param=%x", Map.of("param", "%x")));
        cases.add(Arguments.of("param=%£", Map.of("param", "%£")));
        cases.add(Arguments.of("param=%x&other=foo", Map.of("param", "%x", "other", "foo")));
        cases.add(Arguments.of("param=%£&other=foo", Map.of("param", "%£", "other", "foo")));

        // bad pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret", Map.of("帽�", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret", Map.of("帽�", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret", Map.of("帽�", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret&other=foo", Map.of("帽�", "Beret", "other", "foo")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret&other=foo", Map.of("帽�", "Beret", "other", "foo")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret&other=foo", Map.of("帽�", "Beret", "other", "foo")));

        return cases.stream();
    }

    /**
     * Test of BAD UTF8 ALLOWED, with good input data.
     */
    @ParameterizedTest
    @MethodSource("queryBehaviorsBadUtf8AllowedGood")
    public void testQueryBehaviorsBadUtf8AllowedGood(String input, Map<String, String> expectedParams) throws IOException
    {
        testQueryBehaviorsBadUtf8Allowed(input, expectedParams, false);
    }

    /**
     * Test of BAD UTF8 ALLOWED, with bad input data.
     */
    @ParameterizedTest
    @MethodSource("queryBehaviorsBadUtf8AllowedBad")
    public void testQueryBehaviorsBadUtf8AllowedBad(String input, Map<String, String> expectedParams) throws IOException
    {
        testQueryBehaviorsBadUtf8Allowed(input, expectedParams, true);
    }

    private void testQueryBehaviorsBadUtf8Allowed(String input, Map<String, String> expectedParams, boolean badInput) throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        boolean allowBadEncoding = true;
        boolean allowBadPercent = true;
        boolean allowTruncatedEncoding = true;
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, allowBadEncoding, allowBadPercent, allowTruncatedEncoding);

        assertEquals(badInput, decoder.parse(input), badInput ? "Has coding errors" : "No coding errors");

        assertThat("Field count", fields.getSize(), is(expectedParams.size()));

        for (String key : expectedParams.keySet())
        {
            Fields.Field field = fields.get(key);
            String message = "Fields[%s]".formatted(key);
            assertNotNull(field, message);
            assertEquals(expectedParams.get(key), field.getValue(), message);
        }
    }

    /**
     * The set of allowed query string behaviors collected from Jetty 11. (Good inputs)
     */
    public static Stream<Arguments> queryBehaviorsLegacyAllowedGood()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal cases
        cases.add(Arguments.of("param=aaa", Map.of("param", "aaa")));
        cases.add(Arguments.of("param=aaa&other=foo", Map.of("param", "aaa", "other", "foo")));
        cases.add(Arguments.of("param=", Map.of("param", "")));
        cases.add(Arguments.of("param=&other=foo", Map.of("param", "", "other", "foo")));
        cases.add(Arguments.of("param=%E2%9C%94", Map.of("param", "✔")));
        cases.add(Arguments.of("param=%E2%9C%94&other=foo", Map.of("param", "✔", "other", "foo")));

        // Extra ampersands
        cases.add(Arguments.of("param=aaa&&&", Map.of("param", "aaa")));
        cases.add(Arguments.of("&&&param=aaa", Map.of("param", "aaa")));
        cases.add(Arguments.of("&&param=aaa&&other=foo", Map.of("param", "aaa", "other", "foo")));
        cases.add(Arguments.of("param=aaa&&other=foo&&", Map.of("param", "aaa", "other", "foo")));

        // Encoded ampersands
        cases.add(Arguments.of("param=aaa%26&other=foo", Map.of("param", "aaa&", "other", "foo")));
        cases.add(Arguments.of("param=aaa&%26other=foo", Map.of("param", "aaa", "&other", "foo")));

        // pct-encoded parameter names ("帽子" means "hat" in japanese)
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret", Map.of("帽子", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret&other=foo", Map.of("帽子", "Beret", "other", "foo")));
        cases.add(Arguments.of("other=foo&%E5%B8%BD%E5%AD%90=Beret", Map.of("帽子", "Beret", "other", "foo")));

        // raw unicode parameter names
        cases.add(Arguments.of("€=currency", Map.of("€", "currency")));
        cases.add(Arguments.of("帽子=Beret", Map.of("帽子", "Beret")));

        return cases.stream();
    }

    /**
     * The set of allowed query string behaviors collected from Jetty 11. (Bad inputs)
     */
    public static Stream<Arguments> queryBehaviorsLegacyAllowedBad()
    {
        List<Arguments> cases = new ArrayList<>();

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%9C&other=foo", Map.of("param", "�", "other", "foo")));
        cases.add(Arguments.of("param=%E2", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2&other=foo", Map.of("param", "�", "other", "foo")));

        // Truncated sequence
        cases.add(Arguments.of("param=%E2%82", Map.of("param", "�")));
        cases.add(Arguments.of("param=%E2%82&other=foo", Map.of("param", "�", "other", "foo")));

        // Community Examples
        cases.add(Arguments.of("param=f_%e0%b8", Map.of("param", "f_�")));
        cases.add(Arguments.of("param=f_%e0%b8&other=foo", Map.of("param", "f_�", "other", "foo")));

        // truncated pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret", Map.of("帽�", "Beret")));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret&other=foo", Map.of("帽�", "Beret", "other", "foo")));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsLegacyAllowedGood")
    public void testQueryBehaviorsLegacyAllowedGood(String input, Map<String, String> expectedParams) throws IOException
    {
        testQueryBehaviorsLegacyAllowed(input, expectedParams, false);
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsLegacyAllowedBad")
    public void testQueryBehaviorsLegacyAllowedBad(String input, Map<String, String> expectedParams) throws IOException
    {
        testQueryBehaviorsLegacyAllowed(input, expectedParams, true);
    }

    public void testQueryBehaviorsLegacyAllowed(String input, Map<String, String> expectedParams, boolean badInput) throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPORT);
        boolean allowBadEncoding = false;
        boolean allowBadPercent = false;
        boolean allowTruncatedEncoding = true;
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, allowBadEncoding, allowBadPercent, allowTruncatedEncoding);

        assertEquals(badInput, decoder.parse(input), badInput ? "Has coding errors" : "No coding errors");

        assertThat("Field count", fields.getSize(), is(expectedParams.size()));

        for (String key : expectedParams.keySet())
        {
            Fields.Field field = fields.get(key);
            String message = "Fields[%s]".formatted(key);
            assertNotNull(field, message);
            assertEquals(expectedParams.get(key), field.getValue(), message);
        }
    }

    /**
     * The set of rejected query string behaviors collected from Jetty 11.
     */
    public static Stream<Arguments> queryBehaviorsLegacyRejected()
    {
        List<Arguments> cases = new ArrayList<>();

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C%9"));
        cases.add(Arguments.of("param=%E2%9C%9&other=foo"));
        cases.add(Arguments.of("param=%E2%9C%"));
        cases.add(Arguments.of("param=%E2%9C%&other=foo"));
        cases.add(Arguments.of("param=%E2%9"));
        cases.add(Arguments.of("param=%E2%9&other=foo"));
        cases.add(Arguments.of("param=%E2%"));
        cases.add(Arguments.of("param=%E2%&other=foo"));

        // Tokenized cases
        cases.add(Arguments.of("param=%%TOK%%"));
        cases.add(Arguments.of("param=%%TOK%%&other=foo"));

        // Bad Hex
        cases.add(Arguments.of("param=%xx"));
        cases.add(Arguments.of("param=%xx&other=foo"));

        // Overlong UTF-8 Encoding
        cases.add(Arguments.of("param=%C0%AF"));
        cases.add(Arguments.of("param=%C0%AF&other=foo"));

        // Out of range
        cases.add(Arguments.of("param=%F4%90%80%80"));
        cases.add(Arguments.of("param=%F4%90%80%80&other=foo"));

        // Long surrogate
        cases.add(Arguments.of("param=%ED%A0%80"));
        cases.add(Arguments.of("param=%ED%A0%80&other=foo"));

        // Standalone continuations
        cases.add(Arguments.of("param=%80"));
        cases.add(Arguments.of("param=%80&other=foo"));

        // C1 never starts UTF-8
        cases.add(Arguments.of("param=%C1%BF"));
        cases.add(Arguments.of("param=%C1%BF&other=foo"));

        // E0 must be followed by A0-BF
        cases.add(Arguments.of("param=%E0%9F%80"));
        cases.add(Arguments.of("param=%E0%9F%80&other=foo"));

        // Community Examples
        cases.add(Arguments.of("param=%£"));
        cases.add(Arguments.of("param=%£&other=foo"));

        // truncated pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret")); // Not LEGACY
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret&other=foo")); // Not LEGACY
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret&other=foo"));

        // invalid pct-encoded parameter name
        cases.add(Arguments.of("foo%xx=abc"));
        cases.add(Arguments.of("foo%x=abc"));
        cases.add(Arguments.of("foo%=abc"));

        // utf-16 values (LEGACY has UTF16_ENCODINGS enabled, but it doesn't work for query apparently)
        cases.add(Arguments.of("foo=a%u2192z"));

        // truncated utf-16 values (LEGACY has UTF16_ENCODINGS enabled, but it doesn't work for query apparently)
        cases.add(Arguments.of("foo=a%u219z"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsLegacyRejected")
    public void testQueryBehaviorsLegacyRejected(String input)
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPORT);
        boolean allowBadEncoding = false;
        boolean allowBadPercent = false;
        boolean allowTruncatedEncoding = true;
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, allowBadEncoding, allowBadPercent, allowTruncatedEncoding);

        assertThrows(IllegalArgumentException.class, () -> decoder.parse(input));
    }

    @Test
    public void testShiftJisEncodedString() throws IOException
    {
        assumeTrue(java.nio.charset.Charset.isSupported("Shift_JIS"));

        Fields fields = new Fields();
        Charset japaneseCharset = Charset.forName("Shift_JIS");
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(japaneseCharset);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "name=%82%B1%82%F1%82%C9%82%BF%82%CD";
        assertFalse(decoder.parse(input), "No coding errors");

        String helloInJapanese = "こんにちは";

        assertThat("Field count", fields.getSize(), is(1));
        Fields.Field field = fields.get("name");
        assertNotNull(field, "Fields[name]");
        assertEquals(helloInJapanese, field.getValue(), "Fields[name\\n]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', useHeadersInDisplayName = false,
        textBlock = """
            # query         | expectedName | expectedValue
            a=bad_%e0%b     | a            | bad_�
            b=bad_%e0%ba    | b            | bad_�
            c=short%a       | c            | short%a
            d=b%aam         | d            | b�m
            e=%%TOK%%       | e            | %%TOK%%
            f=%aardvark     | f            | �rdvark
            g=b%ar          | g            | b%ar
            h=end%          | h            | end%
            # This shows how the '&' symbol does not get swallowed by a bad pct-encoding.
            i=%&z=2         | i            | %
            """)
    public void testDecodeAllowBadSequence(String query, String expectedName, String expectedValue) throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);
        assertTrue(decoder.parse(query), "Has coding errors");
        Fields.Field field = fields.get(expectedName);
        assertThat("Name exists", field, notNullValue());
        assertThat("Value", field.getValue(), is(expectedValue));
    }

    /**
     * Tests of raw UTF-8 bytes (not pct-encoded) that arrive.
     * These invalid bytes are automatically converted to replacement characters.
     */
    public static Stream<Arguments> incompleteSequenceCases()
    {
        List<Arguments> cases = new ArrayList<>();

        // Incomplete sequence at the end
        byte[] bytes = {'a', 'b', '=', 'c', -50};
        Map<String, String> expected = new HashMap<>();
        expected.put("ab", "c" + Utf8StringBuilder.REPLACEMENT);
        cases.add(Arguments.of(bytes, expected));

        // Incomplete sequence at the end 2
        bytes = new byte[]{'a', 'b', '=', -50};
        expected = new HashMap<>();
        expected.put("ab", "" + Utf8StringBuilder.REPLACEMENT);
        cases.add(Arguments.of(bytes, expected));

        // Incomplete sequence in name
        bytes = new byte[]{'e', -50, '=', 'f', 'g', '&', 'a', 'b', '=', 'c', 'd'};
        expected = new HashMap<>();
        expected.put("e" + Utf8StringBuilder.REPLACEMENT, "fg");
        expected.put("ab", "cd");
        cases.add(Arguments.of(bytes, expected));

        // Incomplete sequence in value
        bytes = new byte[]{'e', 'f', '=', 'g', -50, '&', 'a', 'b', '=', 'c', 'd'};
        expected = new HashMap<>();
        expected.put("ef", "g" + Utf8StringBuilder.REPLACEMENT);
        expected.put("ab", "cd");
        cases.add(Arguments.of(bytes, expected));

        return cases.stream();
    }

    /**
     * Default UrlParameterDecoder behavior with incomplete sequences, using String input.
     */
    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceDefault(byte[] input, Map<String, String> expected) throws IOException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        // When using new String(byte[], Charset) with invalid bytes, the UTF-8 replacement character is automatically applied.
        String s = new String(input, UTF_8);
        assertFalse(decoder.parse(s), "No coding errors");
        assertThat("Field count", fields.getSize(), is(expected.size()));
        for (String expectedKey : expected.keySet())
        {
            String message = "Field[%s]".formatted(expectedKey);
            Fields.Field field = fields.get(expectedKey);
            assertNotNull(field, message);
            assertEquals(expected.get(expectedKey), field.getValue(), message);
        }
    }

    /**
     * Configured UrlParameterDecoder behavior (allowing all) with incomplete sequences, using String input.
     */
    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceAllowedAsString(byte[] input, Map<String, String> expected) throws Exception
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);

        // When using new String(byte[], Charset) with invalid bytes, the UTF-8 replacement character is automatically applied.
        String s = new String(input, UTF_8);
        assertFalse(decoder.parse(s), "No coding errors");

        assertThat("Field count", fields.getSize(), is(expected.size()));
        for (String expectedKey : expected.keySet())
        {
            String message = "Field[%s]".formatted(expectedKey);
            Fields.Field field = fields.get(expectedKey);
            assertNotNull(field, message);
            assertEquals(expected.get(expectedKey), field.getValue(), message);
        }
    }

    /**
     * Configured UrlParameterDecoder behavior (allowing all) with incomplete sequences, using InputStream input.
     */
    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceAllowedAsInputStream(byte[] input, Map<String, String> expected) throws Exception
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);

        try (InputStream is = new ByteArrayInputStream(input))
        {
            // Internal conversion of raw bytes to char (for parsing) results in automatic UTF-8 replacement character use.
            assertFalse(decoder.parse(is, UTF_8), "No coding errors");

            assertThat("Field count", fields.getSize(), is(expected.size()));
            for (String expectedKey : expected.keySet())
            {
                String message = "Field[%s]".formatted(expectedKey);
                Fields.Field field = fields.get(expectedKey);
                assertNotNull(field, message);
                assertEquals(expected.get(expectedKey), field.getValue(), message);
            }
        }
    }
}
