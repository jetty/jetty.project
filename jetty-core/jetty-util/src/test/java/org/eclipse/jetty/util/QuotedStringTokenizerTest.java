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
        QuotedStringTokenizer commaListOws = QuotedStringTokenizer.builder().delimiters(",").ignoreOptionalWhiteSpace().build();
        QuotedStringTokenizer commaListOwsEmbedded = QuotedStringTokenizer.builder().delimiters(",").ignoreOptionalWhiteSpace().allowEmbeddedQuotes().build();
        QuotedStringTokenizer commaListDelimiters = QuotedStringTokenizer.builder().delimiters(",").returnDelimiters().build();
        QuotedStringTokenizer commaListOwsDelimiters = QuotedStringTokenizer.builder().delimiters(",").ignoreOptionalWhiteSpace().returnDelimiters().build();
        QuotedStringTokenizer commaListOwsEmbeddedQuotes = QuotedStringTokenizer.builder().delimiters(",").ignoreOptionalWhiteSpace().returnQuotes().allowEmbeddedQuotes().build();
        QuotedStringTokenizer commaListEscapeOQ = QuotedStringTokenizer.builder().delimiters(",").allowEscapeOnlyForQuotes().build();

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

        QuotedStringTokenizer tok = QuotedStringTokenizer.builder().delimiters(";").ignoreOptionalWhiteSpace().returnQuotes().allowEmbeddedQuotes().allowEscapeOnlyForQuotes().build();
        Iterator<String> iter = tok.tokenize(contentDisposition);

        assertEquals("form-data", iter.next());
        assertEquals("name=\"fileup\"", iter.next());
        assertEquals("filename=\"C:\\Pictures\\20120504.jpg\"", iter.next());
    }

    public static Stream<Arguments> unquoteOnlySource()
    {
        return Stream.of(
            Arguments.of("---------------------------114782935826962", "---------------------------114782935826962"),
            Arguments.of("---------------------------117031256520586657911714164254", "---------------------------117031256520586657911714164254"),
            Arguments.of("---------------------------2117751712556306154183865432", "---------------------------2117751712556306154183865432"),
            Arguments.of("---------------------------23281168279961", "---------------------------23281168279961"),
            Arguments.of("---------------------------24464570528145", "---------------------------24464570528145"),
            Arguments.of("---------------------------41184676334", "---------------------------41184676334"),
            Arguments.of("---------------------------6390283156237600831344307695", "---------------------------6390283156237600831344307695"),
            Arguments.of("---------------------------7e21b6f2109c", "---------------------------7e21b6f2109c"),
            Arguments.of("---------------------------7e21c038151054", "---------------------------7e21c038151054"),
            Arguments.of("---------------------------7e21df392109c", "---------------------------7e21df392109c"),
            Arguments.of("---------------------------7e223ef2109c", "---------------------------7e223ef2109c"),
            Arguments.of("---------------------------7e225f6151054", "---------------------------7e225f6151054"),
            Arguments.of("---------------------------7e226692109c", "---------------------------7e226692109c"),
            Arguments.of("---------------------------7e226e1b2109c", "---------------------------7e226e1b2109c"),
            Arguments.of("---------------------------7e227e17151054", "---------------------------7e227e17151054"),
            Arguments.of("---------------------------7e25e1e151054", "---------------------------7e25e1e151054"),
            Arguments.of("---------------------------7e28636151054", "---------------------------7e28636151054"),
            Arguments.of("\"8Q4MHJ3LWIQEQQ_OXYU5U9ZLYEH60_CFZQYANCZ\"", "8Q4MHJ3LWIQEQQ_OXYU5U9ZLYEH60_CFZQYANCZ"),
            Arguments.of("\"alternate\"", "alternate"),
            Arguments.of("\"and+%22I%22+quote\"", "and+%22I%22+quote"),
            Arguments.of("\"and \\\"I\\\" quote\"", "and \"I\" quote"),
            Arguments.of("\"attachment\"", "attachment"),
            Arguments.of("B8x_673_DRSeYGTpUMgof-qN1nircWQA", "B8x_673_DRSeYGTpUMgof-qN1nircWQA"),
            Arguments.of("\"%CF%80\"", "%CF%80"),
            Arguments.of("\"_charset_\"", "_charset_"),
            Arguments.of("\"CITY\"", "CITY"),
            Arguments.of("Cku4UvJrPFCXkXjge2a2Y2sgq1bbOa", "Cku4UvJrPFCXkXjge2a2Y2sgq1bbOa"),
            Arguments.of("\"comment\"", "comment"),
            Arguments.of("\"comments\"", "comments"),
            Arguments.of("\"company\"", "company"),
            Arguments.of("\"count\"", "count"),
            Arguments.of("\"description\"", "description"),
            Arguments.of("DHbU6ChASebwm4iE8z9Lakv4ybMmkp", "DHbU6ChASebwm4iE8z9Lakv4ybMmkp"),
            Arguments.of("\"%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1%E3%81%AF%E4%B8%96%E7%95%8C\"", "%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1%E3%81%AF%E4%B8%96%E7%95%8C"),
            Arguments.of("\"%FE%FF%03%C0\"", "%FE%FF%03%C0"),
            Arguments.of("\"file1\"", "file1"),
            Arguments.of("\"file2\"", "file2"),
            Arguments.of("\"file-alt\"", "file-alt"),
            Arguments.of("\"file\"", "file"),
            Arguments.of("\"hello\"", "hello"),
            Arguments.of("\"japanese\"", "japanese"),
            Arguments.of("JettyHttpClientBoundary1275gffetpxz8o0q", "JettyHttpClientBoundary1275gffetpxz8o0q"),
            Arguments.of("JettyHttpClientBoundary14beb4to333d91v8", "JettyHttpClientBoundary14beb4to333d91v8"),
            Arguments.of("JettyHttpClientBoundary1e87p8a551psw1al", "JettyHttpClientBoundary1e87p8a551psw1al"),
            Arguments.of("JettyHttpClientBoundary1evz7ehqg8tvo10h", "JettyHttpClientBoundary1evz7ehqg8tvo10h"),
            Arguments.of("JettyHttpClientBoundary1jcfdl0zps9nf362", "JettyHttpClientBoundary1jcfdl0zps9nf362"),
            Arguments.of("JettyHttpClientBoundary1shlqpw2yahae6jf", "JettyHttpClientBoundary1shlqpw2yahae6jf"),
            Arguments.of("JettyHttpClientBoundary1uz60vid2bq7x1t9", "JettyHttpClientBoundary1uz60vid2bq7x1t9"),
            Arguments.of("JettyHttpClientBoundary9iv9jofnq5dkzmgl", "JettyHttpClientBoundary9iv9jofnq5dkzmgl"),
            Arguments.of("JettyHttpClientBoundaryny8fndkswj5ot6hx", "JettyHttpClientBoundaryny8fndkswj5ot6hx"),
            Arguments.of("L8vdau8TpP0o-AYJDjCuYFQYnjB5gcHIFyap", "L8vdau8TpP0o-AYJDjCuYFQYnjB5gcHIFyap"),
            Arguments.of("\"other\\\"; what=\\\"Something\\\"\"", "other\"; what=\"Something\""),
            Arguments.of("owr6UQGvVNunA_sx2AsizBtyq_uK-OjsQXrF", "owr6UQGvVNunA_sx2AsizBtyq_uK-OjsQXrF"),
            Arguments.of("\"persian-Big5-HKSCS\"", "persian-Big5-HKSCS"),
            Arguments.of("\"persian-Big5\"", "persian-Big5"),
            Arguments.of("\"persian-CESU-8\"", "persian-CESU-8"),
            Arguments.of("\"persian-EUC-JP\"", "persian-EUC-JP"),
            Arguments.of("\"persian-EUC-KR\"", "persian-EUC-KR"),
            Arguments.of("\"persian-GB18030\"", "persian-GB18030"),
            Arguments.of("\"persian-GB2312\"", "persian-GB2312"),
            Arguments.of("\"persian-GBK\"", "persian-GBK"),
            Arguments.of("\"persian-IBM00858\"", "persian-IBM00858"),
            Arguments.of("\"persian-IBM01140\"", "persian-IBM01140"),
            Arguments.of("\"persian-IBM01141\"", "persian-IBM01141"),
            Arguments.of("\"persian-IBM01142\"", "persian-IBM01142"),
            Arguments.of("\"persian-IBM01143\"", "persian-IBM01143"),
            Arguments.of("\"persian-IBM01144\"", "persian-IBM01144"),
            Arguments.of("\"persian-IBM01145\"", "persian-IBM01145"),
            Arguments.of("\"persian-IBM01146\"", "persian-IBM01146"),
            Arguments.of("\"persian-IBM01147\"", "persian-IBM01147"),
            Arguments.of("\"persian-IBM01148\"", "persian-IBM01148"),
            Arguments.of("\"persian-IBM01149\"", "persian-IBM01149"),
            Arguments.of("\"persian-IBM037\"", "persian-IBM037"),
            Arguments.of("\"persian-IBM1026\"", "persian-IBM1026"),
            Arguments.of("\"persian-IBM1047\"", "persian-IBM1047"),
            Arguments.of("\"persian-IBM273\"", "persian-IBM273"),
            Arguments.of("\"persian-IBM277\"", "persian-IBM277"),
            Arguments.of("\"persian-IBM278\"", "persian-IBM278"),
            Arguments.of("\"persian-IBM280\"", "persian-IBM280"),
            Arguments.of("\"persian-IBM284\"", "persian-IBM284"),
            Arguments.of("\"persian-IBM285\"", "persian-IBM285"),
            Arguments.of("\"persian-IBM290\"", "persian-IBM290"),
            Arguments.of("\"persian-IBM297\"", "persian-IBM297"),
            Arguments.of("\"persian-IBM420\"", "persian-IBM420"),
            Arguments.of("\"persian-IBM424\"", "persian-IBM424"),
            Arguments.of("\"persian-IBM437\"", "persian-IBM437"),
            Arguments.of("\"persian-IBM500\"", "persian-IBM500"),
            Arguments.of("\"persian-IBM775\"", "persian-IBM775"),
            Arguments.of("\"persian-IBM850\"", "persian-IBM850"),
            Arguments.of("\"persian-IBM852\"", "persian-IBM852"),
            Arguments.of("\"persian-IBM855\"", "persian-IBM855"),
            Arguments.of("\"persian-IBM857\"", "persian-IBM857"),
            Arguments.of("\"persian-IBM860\"", "persian-IBM860"),
            Arguments.of("\"persian-IBM861\"", "persian-IBM861"),
            Arguments.of("\"persian-IBM862\"", "persian-IBM862"),
            Arguments.of("\"persian-IBM863\"", "persian-IBM863"),
            Arguments.of("\"persian-IBM864\"", "persian-IBM864"),
            Arguments.of("\"persian-IBM865\"", "persian-IBM865"),
            Arguments.of("\"persian-IBM866\"", "persian-IBM866"),
            Arguments.of("\"persian-IBM868\"", "persian-IBM868"),
            Arguments.of("\"persian-IBM869\"", "persian-IBM869"),
            Arguments.of("\"persian-IBM870\"", "persian-IBM870"),
            Arguments.of("\"persian-IBM871\"", "persian-IBM871"),
            Arguments.of("\"persian-IBM918\"", "persian-IBM918"),
            Arguments.of("\"persian-IBM-Thai\"", "persian-IBM-Thai"),
            Arguments.of("\"persian-ISO-2022-JP-2\"", "persian-ISO-2022-JP-2"),
            Arguments.of("\"persian-ISO-2022-JP\"", "persian-ISO-2022-JP"),
            Arguments.of("\"persian-ISO-2022-KR\"", "persian-ISO-2022-KR"),
            Arguments.of("\"persian-ISO-8859-13\"", "persian-ISO-8859-13"),
            Arguments.of("\"persian-ISO-8859-15\"", "persian-ISO-8859-15"),
            Arguments.of("\"persian-ISO-8859-1\"", "persian-ISO-8859-1"),
            Arguments.of("\"persian-ISO-8859-2\"", "persian-ISO-8859-2"),
            Arguments.of("\"persian-ISO-8859-3\"", "persian-ISO-8859-3"),
            Arguments.of("\"persian-ISO-8859-4\"", "persian-ISO-8859-4"),
            Arguments.of("\"persian-ISO-8859-5\"", "persian-ISO-8859-5"),
            Arguments.of("\"persian-ISO-8859-6\"", "persian-ISO-8859-6"),
            Arguments.of("\"persian-ISO-8859-7\"", "persian-ISO-8859-7"),
            Arguments.of("\"persian-ISO-8859-8\"", "persian-ISO-8859-8"),
            Arguments.of("\"persian-ISO-8859-9\"", "persian-ISO-8859-9"),
            Arguments.of("\"persian-JIS_X0201\"", "persian-JIS_X0201"),
            Arguments.of("\"persian-JIS_X0212-1990\"", "persian-JIS_X0212-1990"),
            Arguments.of("\"persian-KOI8-R\"", "persian-KOI8-R"),
            Arguments.of("\"persian-KOI8-U\"", "persian-KOI8-U"),
            Arguments.of("\"persian-Shift_JIS\"", "persian-Shift_JIS"),
            Arguments.of("\"persian-TIS-620\"", "persian-TIS-620"),
            Arguments.of("\"persian-US-ASCII\"", "persian-US-ASCII"),
            Arguments.of("\"persian-UTF-16BE\"", "persian-UTF-16BE"),
            Arguments.of("\"persian-UTF-16LE\"", "persian-UTF-16LE"),
            Arguments.of("\"persian-UTF-16\"", "persian-UTF-16"),
            Arguments.of("\"persian-UTF-32BE\"", "persian-UTF-32BE"),
            Arguments.of("\"persian-UTF-32LE\"", "persian-UTF-32LE"),
            Arguments.of("\"persian-UTF-32\"", "persian-UTF-32"),
            Arguments.of("\"persian-UTF-8\"", "persian-UTF-8"),
            Arguments.of("\"persian-windows-1250\"", "persian-windows-1250"),
            Arguments.of("\"persian-windows-1251\"", "persian-windows-1251"),
            Arguments.of("\"persian-windows-1252\"", "persian-windows-1252"),
            Arguments.of("\"persian-windows-1253\"", "persian-windows-1253"),
            Arguments.of("\"persian-windows-1254\"", "persian-windows-1254"),
            Arguments.of("\"persian-windows-1255\"", "persian-windows-1255"),
            Arguments.of("\"persian-windows-1256\"", "persian-windows-1256"),
            Arguments.of("\"persian-windows-1257\"", "persian-windows-1257"),
            Arguments.of("\"persian-windows-1258\"", "persian-windows-1258"),
            Arguments.of("\"persian-windows-31j\"", "persian-windows-31j"),
            Arguments.of("\"persian-x-Big5-HKSCS-2001\"", "persian-x-Big5-HKSCS-2001"),
            Arguments.of("\"persian-x-Big5-Solaris\"", "persian-x-Big5-Solaris"),
            Arguments.of("\"persian-x-euc-jp-linux\"", "persian-x-euc-jp-linux"),
            Arguments.of("\"persian-x-eucJP-Open\"", "persian-x-eucJP-Open"),
            Arguments.of("\"persian-x-EUC-TW\"", "persian-x-EUC-TW"),
            Arguments.of("\"persian-x-IBM1006\"", "persian-x-IBM1006"),
            Arguments.of("\"persian-x-IBM1025\"", "persian-x-IBM1025"),
            Arguments.of("\"persian-x-IBM1046\"", "persian-x-IBM1046"),
            Arguments.of("\"persian-x-IBM1097\"", "persian-x-IBM1097"),
            Arguments.of("\"persian-x-IBM1098\"", "persian-x-IBM1098"),
            Arguments.of("\"persian-x-IBM1112\"", "persian-x-IBM1112"),
            Arguments.of("\"persian-x-IBM1122\"", "persian-x-IBM1122"),
            Arguments.of("\"persian-x-IBM1123\"", "persian-x-IBM1123"),
            Arguments.of("\"persian-x-IBM1124\"", "persian-x-IBM1124"),
            Arguments.of("\"persian-x-IBM1166\"", "persian-x-IBM1166"),
            Arguments.of("\"persian-x-IBM1364\"", "persian-x-IBM1364"),
            Arguments.of("\"persian-x-IBM1381\"", "persian-x-IBM1381"),
            Arguments.of("\"persian-x-IBM1383\"", "persian-x-IBM1383"),
            Arguments.of("\"persian-x-IBM300\"", "persian-x-IBM300"),
            Arguments.of("\"persian-x-IBM33722\"", "persian-x-IBM33722"),
            Arguments.of("\"persian-x-IBM737\"", "persian-x-IBM737"),
            Arguments.of("\"persian-x-IBM833\"", "persian-x-IBM833"),
            Arguments.of("\"persian-x-IBM834\"", "persian-x-IBM834"),
            Arguments.of("\"persian-x-IBM856\"", "persian-x-IBM856"),
            Arguments.of("\"persian-x-IBM874\"", "persian-x-IBM874"),
            Arguments.of("\"persian-x-IBM875\"", "persian-x-IBM875"),
            Arguments.of("\"persian-x-IBM921\"", "persian-x-IBM921"),
            Arguments.of("\"persian-x-IBM922\"", "persian-x-IBM922"),
            Arguments.of("\"persian-x-IBM930\"", "persian-x-IBM930"),
            Arguments.of("\"persian-x-IBM933\"", "persian-x-IBM933"),
            Arguments.of("\"persian-x-IBM935\"", "persian-x-IBM935"),
            Arguments.of("\"persian-x-IBM937\"", "persian-x-IBM937"),
            Arguments.of("\"persian-x-IBM939\"", "persian-x-IBM939"),
            Arguments.of("\"persian-x-IBM942C\"", "persian-x-IBM942C"),
            Arguments.of("\"persian-x-IBM942\"", "persian-x-IBM942"),
            Arguments.of("\"persian-x-IBM943C\"", "persian-x-IBM943C"),
            Arguments.of("\"persian-x-IBM943\"", "persian-x-IBM943"),
            Arguments.of("\"persian-x-IBM948\"", "persian-x-IBM948"),
            Arguments.of("\"persian-x-IBM949C\"", "persian-x-IBM949C"),
            Arguments.of("\"persian-x-IBM949\"", "persian-x-IBM949"),
            Arguments.of("\"persian-x-IBM950\"", "persian-x-IBM950"),
            Arguments.of("\"persian-x-IBM964\"", "persian-x-IBM964"),
            Arguments.of("\"persian-x-IBM970\"", "persian-x-IBM970"),
            Arguments.of("\"persian-x-ISCII91\"", "persian-x-ISCII91"),
            Arguments.of("\"persian-x-ISO-2022-CN-CNS\"", "persian-x-ISO-2022-CN-CNS"),
            Arguments.of("\"persian-x-ISO-2022-CN-GB\"", "persian-x-ISO-2022-CN-GB"),
            Arguments.of("\"persian-x-iso-8859-11\"", "persian-x-iso-8859-11"),
            Arguments.of("\"persian-x-JIS0208\"", "persian-x-JIS0208"),
            Arguments.of("\"persian-x-Johab\"", "persian-x-Johab"),
            Arguments.of("\"persian-x-MacArabic\"", "persian-x-MacArabic"),
            Arguments.of("\"persian-x-MacCentralEurope\"", "persian-x-MacCentralEurope"),
            Arguments.of("\"persian-x-MacCroatian\"", "persian-x-MacCroatian"),
            Arguments.of("\"persian-x-MacCyrillic\"", "persian-x-MacCyrillic"),
            Arguments.of("\"persian-x-MacDingbat\"", "persian-x-MacDingbat"),
            Arguments.of("\"persian-x-MacGreek\"", "persian-x-MacGreek"),
            Arguments.of("\"persian-x-MacHebrew\"", "persian-x-MacHebrew"),
            Arguments.of("\"persian-x-MacIceland\"", "persian-x-MacIceland"),
            Arguments.of("\"persian-x-MacRomania\"", "persian-x-MacRomania"),
            Arguments.of("\"persian-x-MacRoman\"", "persian-x-MacRoman"),
            Arguments.of("\"persian-x-MacSymbol\"", "persian-x-MacSymbol"),
            Arguments.of("\"persian-x-MacThai\"", "persian-x-MacThai"),
            Arguments.of("\"persian-x-MacTurkish\"", "persian-x-MacTurkish"),
            Arguments.of("\"persian-x-MacUkraine\"", "persian-x-MacUkraine"),
            Arguments.of("\"persian-x-MS932_0213\"", "persian-x-MS932_0213"),
            Arguments.of("\"persian-x-MS950-HKSCS\"", "persian-x-MS950-HKSCS"),
            Arguments.of("\"persian-x-MS950-HKSCS-XP\"", "persian-x-MS950-HKSCS-XP"),
            Arguments.of("\"persian-x-mswin-936\"", "persian-x-mswin-936"),
            Arguments.of("\"persian-x-PCK\"", "persian-x-PCK"),
            Arguments.of("\"persian-x-SJIS_0213\"", "persian-x-SJIS_0213"),
            Arguments.of("\"persian-x-UTF-16LE-BOM\"", "persian-x-UTF-16LE-BOM"),
            Arguments.of("\"persian-X-UTF-32BE-BOM\"", "persian-X-UTF-32BE-BOM"),
            Arguments.of("\"persian-X-UTF-32LE-BOM\"", "persian-X-UTF-32LE-BOM"),
            Arguments.of("\"persian-x-windows-50220\"", "persian-x-windows-50220"),
            Arguments.of("\"persian-x-windows-50221\"", "persian-x-windows-50221"),
            Arguments.of("\"persian-x-windows-874\"", "persian-x-windows-874"),
            Arguments.of("\"persian-x-windows-949\"", "persian-x-windows-949"),
            Arguments.of("\"persian-x-windows-950\"", "persian-x-windows-950"),
            Arguments.of("\"persian-x-windows-iso2022jp\"", "persian-x-windows-iso2022jp"),
            Arguments.of("\"pi\"", "pi"),
            Arguments.of("\"power\"", "power"),
            Arguments.of("qqr2YBBR31U4xVib4vaVuIsrwNY1iw", "qqr2YBBR31U4xVib4vaVuIsrwNY1iw"),
            Arguments.of("QW3F8Fg64P2J2dpfEKGKlX0Q9QF2a8SK_7YH", "QW3F8Fg64P2J2dpfEKGKlX0Q9QF2a8SK_7YH"),
            Arguments.of("\"reporter\"", "reporter"),
            Arguments.of("\"STATE\"", "STATE"),
            Arguments.of("\"text\"", "text"),
            Arguments.of("\"timestamp\"", "timestamp"),
            Arguments.of("u7tfLQaHJEHHUJjnVDbFdc_Oqz4jmkA25mgWd", "u7tfLQaHJEHHUJjnVDbFdc_Oqz4jmkA25mgWd"),
            Arguments.of("\"upload_file\"", "upload_file"),
            Arguments.of("\"user\"", "user"),
            Arguments.of("V9oY7Ug2J-n4sFXLWdb7yd2LtU0hdK36ejhKYh", "V9oY7Ug2J-n4sFXLWdb7yd2LtU0hdK36ejhKYh"),
            Arguments.of("\"value\\\"; what=\\\"whoa\\\"\"", "value\"; what=\"whoa\""),
            Arguments.of("----WebKitFormBoundary2oBNepLIldUG8YwL", "----WebKitFormBoundary2oBNepLIldUG8YwL"),
            Arguments.of("----WebKitFormBoundary46EP6zTN86hbbaJC", "----WebKitFormBoundary46EP6zTN86hbbaJC"),
            Arguments.of("----WebKitFormBoundary56m5uMm4gNcn4rL1", "----WebKitFormBoundary56m5uMm4gNcn4rL1"),
            Arguments.of("----WebKitFormBoundary5trdx3OwYr8uMtbA", "----WebKitFormBoundary5trdx3OwYr8uMtbA"),
            Arguments.of("----WebKitFormBoundaryafpkbdzB5Ciqre2z", "----WebKitFormBoundaryafpkbdzB5Ciqre2z"),
            Arguments.of("----WebKitFormBoundaryD4GyXQgjBRmK3aBz", "----WebKitFormBoundaryD4GyXQgjBRmK3aBz"),
            Arguments.of("----WebKitFormBoundaryDHtjXxgNUcgLjcKs", "----WebKitFormBoundaryDHtjXxgNUcgLjcKs"),
            Arguments.of("----WebKitFormBoundaryEQhxWUv9r38x3LyB", "----WebKitFormBoundaryEQhxWUv9r38x3LyB"),
            Arguments.of("----WebKitFormBoundaryHFCTTESrC7sXQ2Gf", "----WebKitFormBoundaryHFCTTESrC7sXQ2Gf"),
            Arguments.of("----WebKitFormBoundaryjwqONTsAFgubfMZc", "----WebKitFormBoundaryjwqONTsAFgubfMZc"),
            Arguments.of("----WebKitFormBoundarylxcKjAyTlRs3jNP2", "----WebKitFormBoundarylxcKjAyTlRs3jNP2"),
            Arguments.of("----WebKitFormBoundaryN7pYBoDaXhEcUl13", "----WebKitFormBoundaryN7pYBoDaXhEcUl13"),
            Arguments.of("----WebKitFormBoundaryvshQXGBfIsRjfMBN", "----WebKitFormBoundaryvshQXGBfIsRjfMBN"),
            Arguments.of("----WebKitFormBoundaryWl9yEX5Fas0SI2xc", "----WebKitFormBoundaryWl9yEX5Fas0SI2xc"),
            Arguments.of("\"whitespace\"", "whitespace"),
            Arguments.of("xDeLGHDDsXrlJSXfqDmg5IRop7auqTTBXuI", "xDeLGHDDsXrlJSXfqDmg5IRop7auqTTBXuI"),
            Arguments.of("xE8WoYDcbqAfj08bxPk669iK22hMMlZL", "xE8WoYDcbqAfj08bxPk669iK22hMMlZL"),
            Arguments.of("yRxfRSltW63lJPc9oHOOVyCn-SmDG6i4Ts9M4E6", "yRxfRSltW63lJPc9oHOOVyCn-SmDG6i4Ts9M4E6"),
            Arguments.of("z5xWs05oeiE0TAdFlrrlAX5RSgHrHzVcgskrru", "z5xWs05oeiE0TAdFlrrlAX5RSgHrHzVcgskrru"),
            Arguments.of("\"zalgo-16-be\"", "zalgo-16-be"),
            Arguments.of("\"zalgo-16-le\"", "zalgo-16-le"),
            Arguments.of("\"zalgo-16\"", "zalgo-16"),
            Arguments.of("\"zalgo-8\"", "zalgo-8"),
            Arguments.of("\"π\"", "π"),
            Arguments.of("\"こんにちは世界\"", "こんにちは世界")
        );
    }

    /**
     * Test of the QuotedStringTokenizer.unquoteOnly(String) from Jetty 11 and earlier.
     * <p>
     *     This should prove out that the new QuotedStringTokenizer.builder() supports
     *     what is needed to properly behave like the older Jetty 11.
     * </p>
     */
    @ParameterizedTest
    @MethodSource("unquoteOnlySource")
    public void testLegacyUnquoteOnly(String input, String expected)
    {
        QuotedStringTokenizer qst = QuotedStringTokenizer
            .builder()
            .delimiters(";")
            .ignoreOptionalWhiteSpace()
            .allowEscapeOnlyForQuotes()
            .allowEmbeddedQuotes()
            .build();

        assertEquals(expected, qst.unquote(input));
    }
}
