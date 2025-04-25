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
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class UrlParameterDecoderTest
{
    @Test
    public void testUtf8()
        throws Exception
    {
        Fields fields = new Fields();

        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String input = "text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit";
        decoder.parse(input);

        String hex = "E0B89FE0B8ABE0B881E0B8A7E0B894E0B8B2E0B988E0B881E0B89FE0B8A7E0B8ABE0B8AAE0B894E0B8B2E0B988E0B8ABE0B89FE0B881E0B8A7E0B894E0B8AAE0B8B2E0B89FE0B881E0B8ABE0B8A3E0B894E0B989E0B89FE0B8ABE0B899E0B881E0B8A3E0B894E0B8B5";
        String expected = new String(StringUtil.fromHexString(hex), UTF_8);
        assertEquals(expected, fields.getValue("text"));
    }

    @Test
    public void testUtf8MultiByteCodePoint() throws CharacterCodingException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String input = "text=test%C3%A4";
        decoder.parse(input);

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
        assertThrows(expectedThrowableType, () ->
        {
            Fields fields = new Fields();
            CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(charset);
            UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
            decoder.parse(input);
            System.out.println("fields=" + fields);
        });
    }

    /**
     * Example of a parameter with raw UTF-8 unicode which isn't pct-encoded.
     */
    @Test
    public void testBadlyEncodedValue() throws CharacterCodingException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = new Utf8StringBuilder();
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "Name=Euro-€-Symbol";
        decoder.parse(input);
        assertThat("Field count", fields.getSize(), is(1));
        Fields.Field field = fields.get("Name");
        assertNotNull(field, "Fields[Name]");
        assertEquals("Euro-€-Symbol", field.getValue(), "Fields[Name]");
    }

    @Test
    public void testUtf16EncodedString() throws CharacterCodingException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_16);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "name\n=value+%FE%FF%00%30&name1=&name2&n\u00e3me3=value+3";
        decoder.parse(input);

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

        field = fields.get("n\u00e3me3");
        assertNotNull(field, "Fields[n\u00e3me3]");
        assertEquals("value 3", field.getValue(), "Fields[n\u00e3me3]");
    }

    @Test
    public void testShiftJisEncodedString() throws CharacterCodingException
    {
        assumeTrue(java.nio.charset.Charset.isSupported("Shift_JIS"));

        Fields fields = new Fields();
        Charset japaneseCharset = Charset.forName("Shift_JIS");
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(japaneseCharset);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);
        String input = "name=%82%B1%82%F1%82%C9%82%BF%82%CD";
        decoder.parse(input);

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
    public void testDecodeAllowBadSequence(String query, String expectedName, String expectedValue) throws CharacterCodingException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);
        decoder.parse(query);
        Fields.Field field = fields.get(expectedName);
        assertThat("Name exists", field, notNullValue());
        assertThat("Value", field.getValue(), is(expectedValue));
    }

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
     * Default UrlDecoder behavior with incomplete sequences.
     */
    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceDefault(byte[] input, Map<String, String> expected) throws CharacterCodingException
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add);

        String s = new String(input, UTF_8);
        decoder.parse(s);
        assertThat("Field count", fields.getSize(), is(expected.size()));
        for (String expectedKey : expected.keySet())
        {
            String message = "Field[%s]".formatted(expectedKey);
            Fields.Field field = fields.get(expectedKey);
            assertNotNull(field, message);
            assertEquals(expected.get(expectedKey), field.getValue(), message);
        }
    }

    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceAllowedAsString(byte[] input, Map<String, String> expected) throws Exception
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);

        String s = new String(input, UTF_8);
        decoder.parse(s);

        assertThat("Field count", fields.getSize(), is(expected.size()));
        for (String expectedKey : expected.keySet())
        {
            String message = "Field[%s]".formatted(expectedKey);
            Fields.Field field = fields.get(expectedKey);
            assertNotNull(field, message);
            assertEquals(expected.get(expectedKey), field.getValue(), message);
        }
    }

    @ParameterizedTest
    @MethodSource("incompleteSequenceCases")
    public void testUtf8IncompleteSequenceAllowedAsInputStream(byte[] input, Map<String, String> expected) throws Exception
    {
        Fields fields = new Fields();
        CharsetStringBuilder charsetStringBuilder = CharsetStringBuilder.forCharset(UTF_8, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        UrlParameterDecoder decoder = new UrlParameterDecoder(charsetStringBuilder, fields::add, -1, -1, true, true, true);

        try (InputStream is = new ByteArrayInputStream(input))
        {
            decoder.parse(is, UTF_8);

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
