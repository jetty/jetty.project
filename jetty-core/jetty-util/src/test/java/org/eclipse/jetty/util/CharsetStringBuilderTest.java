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
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

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
}
