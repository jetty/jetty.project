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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.util.Utf8Appendable.NOT_UTF8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class Utf8AppendableTest
{
    public static Stream<Arguments> defaultImplementations()
    {
        return Stream.of(
            Arguments.of(Utf8StringBuilder.class),
            Arguments.of(Utf8StringBuffer.class)
        );
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testUtf(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "abcd012345\n\r\u0000\u00a4\u10fb\ufffdjetty";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        System.err.println("bytes = " + StringUtil.toHexString(bytes));
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (byte aByte : bytes)
        {
            buffer.append(aByte);
        }
        assertEquals(source, buffer.getString());
        assertTrue(buffer.getString().endsWith("jetty"));
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testUtf8WithMissingByte(Class<Utf8Appendable> impl) throws Exception
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String source = "abc\u10fb";
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            for (int i = 0; i < bytes.length - 1; i++)
            {
                buffer.append(bytes[i]);
            }
            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testUtf8WithAdditionalByte(Class<Utf8Appendable> impl) throws Exception
    {
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            String source = "abcXX";
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            bytes[3] = (byte)0xc0;
            bytes[4] = (byte)0x00;

            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            for (byte aByte : bytes)
            {
                buffer.append(aByte);
            }

            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testUTF32codes(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "\uD842\uDF9F";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        String jvmcheck = new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);
        assertEquals(source, jvmcheck);

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        buffer.append(bytes, 0, bytes.length);
        String result = buffer.getString();
        assertEquals(source, result);
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testGermanUmlauts(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] bytes = new byte[6];
        bytes[0] = (byte)0xC3;
        bytes[1] = (byte)0xBC;
        bytes[2] = (byte)0xC3;
        bytes[3] = (byte)0xB6;
        bytes[4] = (byte)0xC3;
        bytes[5] = (byte)0xA4;

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (int i = 0; i < bytes.length; i++)
        {
            buffer.append(bytes[i]);
        }

        assertEquals("\u00FC\u00F6\u00E4", buffer.getString());
    }

    /**
     * Test of ARABIC LETTER TEH MARBUTA.
     * (hex code point 0629, decimal code point 1577, hex utf-8 bytes D8 A9)
     * (bidi note, this is a right to left character as well)
     */
    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testArabicTehMarbuta(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] bytes = new byte[4];
        bytes[0] = (byte)0xD8;
        bytes[1] = (byte)0xA9;
        bytes[2] = (byte)0xD8;
        bytes[3] = (byte)0xA9;

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (int i = 0; i < bytes.length; i++)
        {
            buffer.append(bytes[i]);
        }

        assertEquals("ةة", buffer.getString());
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testInvalidUTF8(Class<Utf8Appendable> impl) throws UnsupportedEncodingException
    {
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            buffer.append((byte)0xC2);
            buffer.append((byte)0xC2);

            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testInvalidZeroUTF8(Class<Utf8Appendable> impl) throws UnsupportedEncodingException
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            buffer.append((byte)0xC0);
            buffer.append((byte)0x80);

            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testInvalidAlternateDotEncodingUTF8(Class<Utf8Appendable> impl) throws UnsupportedEncodingException
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            buffer.append((byte)0x2f);
            buffer.append((byte)0xc0);
            buffer.append((byte)0xae);
            buffer.append((byte)0x2e);
            buffer.append((byte)0x2f);

            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testFail1(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5");
        byte[] part2 = StringUtil.fromHexString("f4908080"); // INVALID
        // Here for test tracking reasons, not needed to satisfy test
        // byte[] part3 = StringUtil.fromHexString("656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);

        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            // Part 2 is invalid
            buffer.append(part2, 0, part2.length);
            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testFail2(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5f4");
        byte[] part2 = StringUtil.fromHexString("90"); // INVALID
        // Here for test search/tracking reasons, not needed to satisfy test
        // byte[] part3 = StringUtil.fromHexString("8080656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);

        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            // Part 2 is invalid
            buffer.append(part2, 0, part2.length);
            buffer.getString(true, NOT_UTF8);
        });
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testPartialUnsplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String seq2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";

        utf8.append(BufferUtil.toBuffer(seq1, StandardCharsets.UTF_8));
        String ret1 = utf8.getString(false);

        utf8.append(BufferUtil.toBuffer(seq2, StandardCharsets.UTF_8));
        String ret2 = utf8.getString(false);

        assertThat("Seq1", ret1, is(seq1));
        assertThat("Seq2", ret2, is(seq2));
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testPartialSplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String ret1 = utf8.getString(false);

        utf8.append(StringUtil.fromHexString(seq2));
        String ret2 = utf8.getString(false);

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }

    @ParameterizedTest
    @MethodSource("defaultImplementations")
    public void testPartialSplitCodepointWithNoBuf(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String ret1 = utf8.getString(false);
        String ret2 = utf8.getString(false);
        utf8.append(StringUtil.fromHexString(seq2));
        String ret3 = utf8.getString(false);

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is(""));
        assertThat("Seq3", ret3, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }

    public static Stream<Arguments> appendWithReplacementSource()
    {
        List<Arguments> sources = new ArrayList<>();

        boolean matchJavaBehavior = true;

        sources.add(Arguments.of("00", "\u0000", matchJavaBehavior)); // null control char
        sources.add(Arguments.of("E282AC", "€", matchJavaBehavior)); // euro symbol
        sources.add(Arguments.of("EFBFBD", "�", matchJavaBehavior)); // the replacement character itself
        // "overlong" UTF-8 sequences - (the utf-8 codepage specifies that C0/C1 should be rejected for "overlong" encoding)
        // "hi" (ascii) is bytes 0x68 0x69, which can be represented in overlong encoding of
        // C1 (11000001) + A8 (10101000) = 'h'
        // C1 (11000001) + A9 (10101001) = 'i'
        sources.add(Arguments.of("C1A8C1A9", "����", matchJavaBehavior));
        // "()" (ascii) is bytes 0x28 0x29, which can be represented in overlong encoding of
        // C0 (11000001) + A8 (10101000) = '('
        // C0 (11000001) + A9 (10101001) = ')'
        sources.add(Arguments.of("C0A8C0A9", "����", matchJavaBehavior));
        // the following expected results are gathered from URI.create(input).getPath()
        // which is consistent with StandardCharsets.UTF_8.newDecoder().onMalformed(REPLACE).decode()
        sources.add(Arguments.of("D8002F", "�\u0000/", matchJavaBehavior));
        sources.add(Arguments.of("D82F", "�/", matchJavaBehavior)); // incomplete sequence followed by normal char
        sources.add(Arguments.of("C328", "�(", matchJavaBehavior)); // incomplete sequence followed by normal char
        sources.add(Arguments.of("A0A1", "��", matchJavaBehavior)); // invalid 2 octet sequence
        sources.add(Arguments.of("E228A1", "�(�", matchJavaBehavior)); // incomplete + normal + incomplete
        sources.add(Arguments.of("E28228", "�(", matchJavaBehavior));  // invalid 3 octet sequence
        sources.add(Arguments.of("F0288CBC", "�(��", matchJavaBehavior)); // invalid 4 octet sequence
        sources.add(Arguments.of("F09028BC", "�(�", matchJavaBehavior)); // invalid 4 octet sequence
        sources.add(Arguments.of("F0288C28", "�(�(", matchJavaBehavior));  // invalid 4 octet sequence
        sources.add(Arguments.of("F8A1A1A1A1", "�����", matchJavaBehavior));  // valid sequence, but not unicode
        sources.add(Arguments.of("FCA1A1A1A1A1", "������", matchJavaBehavior));  // valid sequence, but not unicode
        sources.add(Arguments.of("F8A1A1A1", "����", matchJavaBehavior)); // F8 codepage not supported
        sources.add(Arguments.of("C0AF", "��", matchJavaBehavior));
        sources.add(Arguments.of("F08080AF", "����", matchJavaBehavior));
        sources.add(Arguments.of("FFFFFF", "���", matchJavaBehavior)); // FF codepage never assigned meaning in utf-8
        sources.add(Arguments.of("f8808080af", "�����", matchJavaBehavior)); // F8 codepage not supported
        sources.add(Arguments.of("e080af", "���", matchJavaBehavior));
        sources.add(Arguments.of("F4908080", "����", matchJavaBehavior));
        sources.add(Arguments.of("fbbfbfbfbf", "�����", matchJavaBehavior)); // FB codepage not supported
        sources.add(Arguments.of("10FFFF", "\u0010��", matchJavaBehavior));
        // use of UTF-16 High Surrogates (in codepoint form)
        sources.add(Arguments.of("da07", "�\u0007", matchJavaBehavior));
        sources.add(Arguments.of("d807", "�\u0007", matchJavaBehavior));

        // The following are outliers, and produce extra replacement characters when
        // compared the Java replacement character behaviors.
        sources.add(Arguments.of("EDA080", "���", !matchJavaBehavior));
        sources.add(Arguments.of("CeBaE1BdB9Cf83CeBcCeB5EdA080656469746564", "κόσμε���edited", !matchJavaBehavior));
        // decoded UTF-16 High Surrogate "\ud807" (in UTF-8 form)
        sources.add(Arguments.of("EDA087", "���", !matchJavaBehavior));
        return sources.stream();
    }

    @ParameterizedTest
    @MethodSource("appendWithReplacementSource")
    public void testBadUtf8(String inputHex, String expectedResult, boolean compareWithJavaCharsetDecoder)
    {
        byte[] inputBytes = StringUtil.fromHexString(inputHex);
        if (compareWithJavaCharsetDecoder)
        {
            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();
            utf8Decoder.onMalformedInput(CodingErrorAction.REPLACE);
            try
            {
                CharBuffer charBuffer = utf8Decoder.decode(ByteBuffer.wrap(inputBytes));
                String charsetDecoderResult = charBuffer.toString();
                assertThat("CharsetDecoder result", charsetDecoderResult, is(expectedResult));
            }
            catch (CharacterCodingException e)
            {
                fail("Should not have thrown while in REPLACE mode", e);
            }
        }

        Utf8StringBuilder utf8Builder = new Utf8StringBuilder();
        for (byte b: inputBytes)
        {
            try
            {
                utf8Builder.appendByte(b);
            }
            catch (IOException e)
            {
                fail("Should not have thrown while in REPLACE mode", e);
            }
        }
        String ourResult = utf8Builder.getString(false);
        assertThat("Utf8Appendable with REPLACE mode", ourResult, is(expectedResult));
    }
}
