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

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * URL Encoding / Decoding Tests
 */
// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class URLEncodedTest
{
    @TestFactory
    public Iterator<DynamicTest> testUrlEncoded()
    {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("Initially not empty", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            assertEquals(0, urlEncoded.size());
        }));

        tests.add(dynamicTest("Not empty after decode(\"\")", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(0, urlEncoded.size());
        }));

        tests.add(dynamicTest("Simple encode", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name1=Value1", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "simple param size");
            assertEquals("Name1=Value1", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "simple encode");
            assertEquals("Value1", urlEncoded.getString("Name1"), "simple get");
        }));

        tests.add(dynamicTest("Dangling param", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name2=", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "dangling param size");
            assertEquals("Name2", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "dangling encode");
            assertEquals("", urlEncoded.getString("Name2"), "dangling get");
        }));

        tests.add(dynamicTest("noValue param", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name3", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "noValue param size");
            assertEquals("Name3", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "noValue encode");
            assertEquals("", urlEncoded.getString("Name3"), "noValue get");
        }));

        tests.add(dynamicTest("badly encoded param", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name4=V\u0629lue+4%21", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "encoded param size");
            assertEquals("Name4=V%D8%A9lue+4%21", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "encoded encode");
            assertEquals("V\u0629lue 4!", urlEncoded.getString("Name4"), "encoded get");
        }));

        tests.add(dynamicTest("encoded param 1", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name4=Value%2B4%21", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "encoded param size");
            assertEquals("Name4=Value%2B4%21", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "encoded encode");
            assertEquals("Value+4!", urlEncoded.getString("Name4"), "encoded get");
        }));

        tests.add(dynamicTest("encoded param 2", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name4=Value+4%21%20%214", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "encoded param size");
            assertEquals("Name4=Value+4%21+%214", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "encoded encode");
            assertEquals("Value 4! !4", urlEncoded.getString("Name4"), "encoded get");
        }));

        tests.add(dynamicTest("multi param", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name5=aaa&Name6=bbb", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(2, urlEncoded.size(), "multi param size");
            assertTrue(UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false).equals("Name5=aaa&Name6=bbb") ||
                    UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false).equals("Name6=bbb&Name5=aaa"),
                "multi encode " + UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false));
            assertEquals("aaa", urlEncoded.getString("Name5"), "multi get");
            assertEquals("bbb", urlEncoded.getString("Name6"), "multi get");
        }));

        tests.add(dynamicTest("multiple value encoded", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name7=aaa&Name7=b%2Cb&Name7=ccc", urlEncoded, UrlEncoded.ENCODING);
            assertEquals("Name7=aaa&Name7=b%2Cb&Name7=ccc", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "multi encode");
            assertEquals("aaa,b,b,ccc", urlEncoded.getString("Name7"), "list get all");
            assertEquals("aaa", urlEncoded.getValues("Name7").get(0), "list get");
            assertEquals("b,b", urlEncoded.getValues("Name7").get(1), "list get");
            assertEquals("ccc", urlEncoded.getValues("Name7").get(2), "list get");
        }));

        tests.add(dynamicTest("encoded param", () ->
        {
            MultiMap<String> urlEncoded = new MultiMap<>();
            urlEncoded.clear();
            UrlEncoded.decodeTo("Name8=xx%2C++yy++%2Czz", urlEncoded, UrlEncoded.ENCODING);
            assertEquals(1, urlEncoded.size(), "encoded param size");
            assertEquals("Name8=xx%2C++yy++%2Czz", UrlEncoded.encode(urlEncoded, UrlEncoded.ENCODING, false), "encoded encode");
            assertEquals("xx,  yy  ,zz", urlEncoded.getString("Name8"), "encoded get");
        }));

        return tests.iterator();
    }

    @TestFactory
    public Iterator<DynamicTest> testUrlEncodedStream()
    {
        String[][] charsets = new String[][]
            {
                {StringUtil.__UTF8, null, "%30"},
                {StringUtil.__ISO_8859_1, StringUtil.__ISO_8859_1, "%30"},
                {StringUtil.__UTF8, StringUtil.__UTF8, "%30"},
                {StringUtil.__UTF16, StringUtil.__UTF16, "%00%30"},
            };

        // Note: "%30" -> decode -> "0"

        List<DynamicTest> tests = new ArrayList<>();

        for (String[] params : charsets)
        {
            tests.add(dynamicTest(params[0] + ">" + params[1] + "|" + params[2],
                () ->
                {
                    try (ByteArrayInputStream in = new ByteArrayInputStream(("name\n=value+" + params[2] + "&name1=&name2&n\u00e3me3=value+3").getBytes(params[0])))
                    {
                        MultiMap<String> m = new MultiMap<>();
                        UrlEncoded.decodeTo(in, m, params[1] == null ? null : Charset.forName(params[1]), -1, -1);
                        assertEquals(4, m.size(), params[1] + " stream length");
                        assertThat(params[1] + " stream name\\n", m.getString("name\n"), is("value 0"));
                        assertThat(params[1] + " stream name1", m.getString("name1"), is(""));
                        assertThat(params[1] + " stream name2", m.getString("name2"), is(""));
                        assertThat(params[1] + " stream n\u00e3me3", m.getString("n\u00e3me3"), is("value 3"));
                    }
                }
            ));
        }

        if (java.nio.charset.Charset.isSupported("Shift_JIS"))
        {
            tests.add(dynamicTest("Shift_JIS",
                () ->
                {
                    try (ByteArrayInputStream in2 = new ByteArrayInputStream("name=%83e%83X%83g".getBytes(StandardCharsets.ISO_8859_1)))
                    {
                        MultiMap<String> m2 = new MultiMap<>();
                        UrlEncoded.decodeTo(in2, m2, Charset.forName("Shift_JIS"), -1, -1);
                        assertEquals(1, m2.size(), "stream length");
                        assertEquals("\u30c6\u30b9\u30c8", m2.getString("name"), "stream name");
                    }
                }
            ));
        }

        return tests.iterator();
    }

    @Test
    @EnabledIfSystemProperty(named = "org.eclipse.jetty.util.UrlEncoding.charset", matches = "\\p{Alnum}")
    public void testCharsetViaSystemProperty()
        throws Exception
    {
        try (ByteArrayInputStream in3 = new ByteArrayInputStream("name=libell%E9".getBytes(StringUtil.__ISO_8859_1)))
        {
            MultiMap<String> m3 = new MultiMap<>();
            Charset nullCharset = null; // use the one from the system property
            UrlEncoded.decodeTo(in3, m3, nullCharset, -1, -1);
            assertEquals("libell\u00E9", m3.getString("name"), "stream name");
        }
    }

    @Test
    public void testUtf8()
        throws Exception
    {
        MultiMap<String> urlEncoded = new MultiMap<>();
        assertEquals(0, urlEncoded.size(), "Empty");

        urlEncoded.clear();
        UrlEncoded.decodeTo("text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit", urlEncoded, UrlEncoded.ENCODING);

        String hex = "E0B89FE0B8ABE0B881E0B8A7E0B894E0B8B2E0B988E0B881E0B89FE0B8A7E0B8ABE0B8AAE0B894E0B8B2E0B988E0B8ABE0B89FE0B881E0B8A7E0B894E0B8AAE0B8B2E0B89FE0B881E0B8ABE0B8A3E0B894E0B989E0B89FE0B8ABE0B899E0B881E0B8A3E0B894E0B8B5";
        String expected = new String(TypeUtil.fromHexString(hex), "utf-8");
        assertEquals(expected, urlEncoded.getString("text"));
    }

    @Test
    public void testUtf8MultiByteCodePoint()
    {
        String input = "text=test%C3%A4";
        MultiMap<String> urlEncoded = new MultiMap<>();
        UrlEncoded.decodeTo(input, urlEncoded, UrlEncoded.ENCODING);

        // http://www.ltg.ed.ac.uk/~richard/utf-8.cgi?input=00e4&mode=hex
        // Should be "testä"
        // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS

        String expected = "test\u00e4";
        assertThat(urlEncoded.getString("text"), is(expected));
    }

    public static Stream<Arguments> invalidTestData()
    {
        ArrayList<Arguments> data = new ArrayList<>();
        data.add(Arguments.of("Name=xx%zzyy", UTF_8, IllegalArgumentException.class));
        data.add(Arguments.of("Name=%FF%FF%FF", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        data.add(Arguments.of("Name=%EF%EF%EF", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        data.add(Arguments.of("Name=%E%F%F", UTF_8, IllegalArgumentException.class));
        data.add(Arguments.of("Name=x%", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        data.add(Arguments.of("Name=x%2", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        data.add(Arguments.of("Name=xxx%", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        data.add(Arguments.of("name=X%c0%afZ", UTF_8, Utf8Appendable.NotUtf8Exception.class));
        return data.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidTestData")
    public void testInvalidDecode(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            UrlEncoded.decodeTo(inputString, new MultiMap<>(), charset);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidTestData")
    public void testInvalidDecodeUtf8ToMap(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            MultiMap<String> map = new MultiMap<>();
            UrlEncoded.decodeUtf8To(inputString, map);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidTestData")
    public void testInvalidDecodeTo(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            MultiMap<String> map = new MultiMap<>();
            UrlEncoded.decodeTo(inputString, map, charset);
        });
    }
}
