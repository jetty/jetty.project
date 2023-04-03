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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class Utf8StringBuilderTest
{
    @Test
    public void testUtf() throws Exception
    {
        String source = "abcd012345\n\r\u0000¤჻\ufffdjetty";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        for (byte aByte : bytes)
        {
            utf8.append(aByte);
        }
        assertEquals(source, utf8.toCompleteString());
        assertTrue(utf8.toCompleteString().endsWith("jetty"));
    }

    @Test
    public void testUtf8WithMissingByte() throws Exception
    {
        String source = "abc჻";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        for (int i = 0; i < bytes.length - 1; i++)
        {
            utf8.append(bytes[i]);
        }
        assertThat(utf8.toPartialString(), equalTo("abc"));
        assertThat(utf8.toCompleteString(), equalTo("abc�"));
        assertThrows(CharacterCodingException.class, utf8::build);
    }

    @Test
    public void testUtf8WithAdditionalByte() throws Exception
    {
        String source = "abcXX";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        bytes[3] = (byte)0xc0;
        bytes[4] = (byte)0x00;

        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        for (byte aByte : bytes)
            utf8.append(aByte);

        assertThat(utf8.toPartialString(), equalTo("abc�\000"));
        assertThat(utf8.toCompleteString(), equalTo("abc�\000"));
        assertThrows(CharacterCodingException.class, utf8::build);
    }

    @Test
    public void testUTF32codes() throws Exception
    {
        String source = "\uD842\uDF9F";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        String jvmcheck = new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);
        assertEquals(source, jvmcheck);

        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        utf8.append(bytes, 0, bytes.length);
        String result = utf8.toCompleteString();
        assertEquals(source, result);
    }

    @Test
    public void testGermanUmlauts() throws Exception
    {
        byte[] bytes = new byte[6];
        bytes[0] = (byte)0xC3;
        bytes[1] = (byte)0xBC;
        bytes[2] = (byte)0xC3;
        bytes[3] = (byte)0xB6;
        bytes[4] = (byte)0xC3;
        bytes[5] = (byte)0xA4;

        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        for (byte aByte : bytes)
            utf8.append(aByte);

        assertEquals("üöä", utf8.toCompleteString());
    }

    @Test
    public void testInvalidUTF8() throws Exception
    {
        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        utf8.append((byte)0xC2); // start of sequence
        utf8.append((byte)0xC2); // start of another sequence
        assertThat(utf8.toPartialString(), equalTo("�")); // only first sequence is reported as BAD
        assertThat(utf8.toCompleteString(), equalTo("��")); // now both sequences are reported as BAD
        assertThrows(CharacterCodingException.class, utf8::build);
    }

    @Test
    public void testInvalidZeroUTF8() throws Exception
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        utf8.append((byte)0xC0);
        utf8.append((byte)0x80);
        assertThat(utf8.toPartialString(), equalTo("��"));
        assertThat(utf8.toCompleteString(), equalTo("��"));
        assertThrows(CharacterCodingException.class, utf8::build);
    }

    @Test
    public void testInvalidAlternateDotEncodingUTF8() throws Exception
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        utf8.append((byte)0x2f);
        utf8.append((byte)0xc0);
        utf8.append((byte)0xae);
        utf8.append((byte)0x2e);
        utf8.append((byte)0x2f);

        assertThat(utf8.toPartialString(), equalTo("/��./"));
        assertThat(utf8.toCompleteString(), equalTo("/��./"));
        assertThrows(CharacterCodingException.class, utf8::build);
    }

    @Test
    public void testFastFail1() throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5");
        byte[] part2 = StringUtil.fromHexString("f4908080"); // INVALID
        // Here for test tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("656469746564");

        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        // Part 1 is valid
        utf8.append(part1, 0, part1.length);
        assertFalse(utf8.hasCodingErrors());

        // Part 2 is invalid
        utf8.append(part2, 0, part2.length);
        assertTrue(utf8.hasCodingErrors());
    }

    @Test
    public void testFastFail2() throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5f4");
        byte[] part2 = StringUtil.fromHexString("90"); // INVALID
        // Here for test search/tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("8080656469746564");

        Utf8StringBuilder utf8 = new Utf8StringBuilder();
        // Part 1 is valid
        utf8.append(part1, 0, part1.length);
        assertFalse(utf8.hasCodingErrors());

        // Part 2 is invalid
        utf8.append(part2, 0, part2.length);
        assertTrue(utf8.hasCodingErrors());
    }

    @Test
    public void testPartialSplitSingleCodepoint() throws Exception
    {
        // GOTHIC LETTER HWAIR
        final String gothicUnicode = "𐍈";
        // Lets use a 4 byte utf-8 sequence
        byte[] utf8Bytes = gothicUnicode.getBytes(StandardCharsets.UTF_8);
        assertThat(utf8Bytes.length, is(4));

        // First payload is 2 bytes, second payload is 2 bytes
        ByteBuffer codepointStart = BufferUtil.toBuffer(utf8Bytes, 0, 2);
        ByteBuffer codepointFinish = BufferUtil.toBuffer(utf8Bytes, 2, 2);

        Utf8StringBuilder utf8 = new Utf8StringBuilder();

        utf8.append(codepointStart);
        String partial1 = utf8.toPartialString();
        utf8.partialReset();

        utf8.append(codepointFinish);
        String partial2 = utf8.toPartialString();
        utf8.partialReset();

        assertThat("Seq1", partial1, is("")); // nothing decoded yet
        assertThat("Seq2", partial2, is(gothicUnicode)); // completed decode
    }

    @Test
    public void testPartialUnsplitCodepoint() throws Exception
    {
        Utf8StringBuilder utf8 = new Utf8StringBuilder();

        String seq1 = "Hello-습@쎟쎤";
        String seq2 = "쎼쎠쎡-UTF-8!!";

        utf8.append(BufferUtil.toBuffer(seq1, StandardCharsets.UTF_8));
        String partial1 = utf8.toPartialString();
        utf8.partialReset();
        String ret1 = partial1;

        utf8.append(BufferUtil.toBuffer(seq2, StandardCharsets.UTF_8));
        String partial = utf8.toPartialString();
        utf8.partialReset();
        String ret2 = partial;

        assertThat("Seq1", ret1, is(seq1));
        assertThat("Seq2", ret2, is(seq2));
    }

    @Test
    public void testPartialSplitCodepoint() throws Exception
    {
        Utf8StringBuilder utf8 = new Utf8StringBuilder();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String partial1 = utf8.toPartialString();
        utf8.partialReset();
        String ret1 = partial1;

        utf8.append(StringUtil.fromHexString(seq2));
        String partial = utf8.toPartialString();
        utf8.partialReset();
        String ret2 = partial;

        assertThat("Seq1", ret1, is("Hello-습@쎟"));
        assertThat("Seq2", ret2, is("쎤쎼쎠쎡-UTF-8!!"));
    }

    @Test
    public void testPartialSplitCodepointWithNoBuf() throws Exception
    {
        Utf8StringBuilder utf8 = new Utf8StringBuilder();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String partial2 = utf8.toPartialString();
        utf8.partialReset();
        String ret1 = partial2;
        String partial1 = utf8.toPartialString();
        utf8.partialReset();
        String ret2 = partial1;
        utf8.append(StringUtil.fromHexString(seq2));
        String partial = utf8.toPartialString();
        utf8.partialReset();
        String ret3 = partial;

        assertThat("Seq1", ret1, is("Hello-습@쎟"));
        assertThat("Seq2", ret2, is(""));
        assertThat("Seq3", ret3, is("쎤쎼쎠쎡-UTF-8!!"));
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
        String ourResult = utf8Builder.toCompleteString();
        assertThat("Utf8Appendable with REPLACE mode", ourResult, is(expectedResult));
    }
}
