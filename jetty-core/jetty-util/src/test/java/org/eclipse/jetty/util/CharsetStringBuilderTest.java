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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
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

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class CharsetStringBuilderTest
{
    public static Stream<Arguments> tests()
    {
        return Stream.of(
            Arguments.of("Hello World \uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-16 Æ\tÿ!!!", StandardCharsets.UTF_16),
            Arguments.of("Hello World \uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8 Æ\tÿ!!!", StandardCharsets.UTF_8),
            Arguments.of("Now is the time for all good men to test US_ASCII \r\n\t!", StandardCharsets.US_ASCII),
            Arguments.of("How Now Brown Cow. Test iso 8859 Æ\tÿ!", StandardCharsets.ISO_8859_1)
        );
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void testBuilder(String test, Charset charset) throws Exception
    {
        byte[] bytes = test.getBytes(charset);

        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);

        builder.append(bytes);
        assertThat(builder.build(), equalTo(test));

        for (byte b : bytes)
        {
            builder.append(b);
        }
        assertThat(builder.build(), equalTo(test));

        builder.append(bytes[0]);
        builder.append(bytes, 1, bytes.length - 1);
        assertThat(builder.build(), equalTo(test));
    }

    public static Stream<Charset> charsets()
    {
        return Stream.of(
            StandardCharsets.UTF_8,
            StandardCharsets.ISO_8859_1,
            StandardCharsets.US_ASCII,
            StandardCharsets.UTF_16
        );
    }

    @ParameterizedTest
    @MethodSource("charsets")
    public void testAppendByteBuffersOnly(Charset charset) throws Exception
    {
        String input = "123456789ABC";
        // Generate a ByteBuffer encoded with the provided charset of the input String.
        CharsetEncoder encoder = charset.newEncoder();
        ByteBuffer bb = ByteBuffer.allocate(input.length() * 4);
        encoder.encode(CharBuffer.wrap(input), bb, true);
        bb.flip();

        // using only append(ByteBuffer) recreate the input
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        int sliceSize = 3;
        int len = bb.remaining();
        int offset = 0;
        while (offset < len)
        {
            ByteBuffer slice = bb.slice();
            slice.position(offset);
            int limit = Math.min(slice.position() + sliceSize, len);
            slice.limit(limit);
            builder.append(slice);
            offset = slice.position();
        }

        assertThat(builder.build(), is(input));
    }

    @ParameterizedTest
    @MethodSource("charsets")
    public void testAppendByteOnly(Charset charset) throws Exception
    {
        String input = "123456789ABC";

        // Generate a byte buffer encoded with the provided charset of the input String.
        byte[] buf = input.getBytes(charset);

        // using only append(byte) recreate the input
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        for (byte b : buf)
        {
            builder.append(b);
        }

        assertThat(builder.build(), is(input));
    }

    @ParameterizedTest
    @MethodSource("charsets")
    public void testAppendByteOffsetLengthOnly(Charset charset) throws Exception
    {
        String input = "123456789ABC";

        // Generate a byte buffer encoded with the provided charset of the input String.
        byte[] buf = input.getBytes(charset);

        // using only append(byte, offset, length) recreate the input
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        int sliceSize = 3;
        int offset = 0;
        while (offset < buf.length)
        {
            int len = Math.min(sliceSize, buf.length - offset);
            builder.append(buf, offset, len);
            offset += sliceSize;
        }

        assertThat(builder.build(), is(input));
    }

    @ParameterizedTest
    @MethodSource("charsets")
    public void testAppendCharOnly(Charset charset) throws Exception
    {
        String input = "123456789ABC";

        // using only append(char) recreate the input
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        for (char c : input.toCharArray())
        {
            builder.append(c);
        }

        assertThat(builder.build(), is(input));
    }

    @ParameterizedTest
    @MethodSource("charsets")
    public void testAppendCharSequenceOffsetLengthOnly(Charset charset) throws Exception
    {
        String input = "123456789ABC";

        // using only append(CharSequence, offset, length) recreate the input
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        char[] chars = input.toCharArray();
        int sliceSize = 3;
        int offset = 0;
        while (offset < chars.length)
        {
            int len = Math.min(sliceSize, chars.length - offset);
            builder.append(input, offset, len);
            offset += sliceSize;
        }

        assertThat(builder.build(), is(input));
    }

    @Test
    public void testSjisEncoding() throws CharacterCodingException
    {
        Charset sjisCharset = Charset.forName("Shift_JIS");
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(sjisCharset);

        builder.append((byte)0x83);
        builder.append((char)'z');

        assertEquals("ホ", builder.build());
    }

    public static Stream<Arguments> japaneseCharsetTests()
    {
        List<Arguments> args = new ArrayList<>();
        for (Charset charset : List.of(
            StandardCharsets.UTF_8,
            StandardCharsets.UTF_16,
            Charset.forName("Shift_JIS"),
            Charset.forName("EUC-JP")
        ))
        {
            for (String string : List.of(
                // Has ASCII 'O' (0x30) in UTF-16
                "ホ",
                // Has ASCII 'O' (0x30) in UTF-16
                // Has ASCII 'J' (0x4A), ASCII '^' (0x5E), and ASCII 'i' (0x69) in Shift_JIS
                "カタカナ",
                // Has ASCII 'v' (0x76) in UTF-16
                "ｶﾞｯﾂﾎﾟｰｽﾞ"
            ))
            {
                args.add(Arguments.of(charset, string));
            }
        }
        return args.stream();
    }

    /**
     * Paranoid test, showing badly mixed API usage.
     */
    @ParameterizedTest
    @MethodSource("japaneseCharsetTests")
    public void testJapaneseCharsetsMixedAppend(Charset charset, String string) throws Exception
    {
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        byte[] bytes = string.getBytes(charset);
        for (byte b : bytes)
        {
            // if a raw byte is one of the ASCII characters, add it as a character??
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9')
                builder.append((char)b);
            else
                builder.append(b);
        }
        assertThat(builder.build(), is(string));
    }

    /**
     * This mimics the usage behavior as seen in ContentSourceString.
     */
    @ParameterizedTest
    @MethodSource("japaneseCharsetTests")
    public void testJapaneseCharsetsAppendByteBufferOnly(Charset charset, String string) throws Exception
    {
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        byte[] bytes = string.getBytes(charset);
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        // Let's write it in two ByteBuffer's
        int midway = buf.remaining() / 2;

        ByteBuffer slice1 = buf.slice();
        slice1.position(0);
        slice1.limit(midway);

        ByteBuffer slice2 = buf.slice();
        slice2.position(midway);

        builder.append(slice1);
        builder.append(slice2);

        assertThat(builder.build(), is(string));
    }

    /**
     * This mimics how the UrlParameterDecoder operates.
     * (char by char, not byte by byte)
     */
    @ParameterizedTest
    @MethodSource("japaneseCharsetTests")
    public void testJapaneseCharsetsAppendCharOnly(Charset charset, String string) throws Exception
    {
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        for (char c: string.toCharArray())
        {
            builder.append(c);
        }
        assertThat(builder.build(), is(string));
    }
}
