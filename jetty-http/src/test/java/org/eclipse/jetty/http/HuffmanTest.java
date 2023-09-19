//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.jetty.http.compression.EncodingException;
import org.eclipse.jetty.http.compression.HuffmanDecoder;
import org.eclipse.jetty.http.compression.HuffmanEncoder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HuffmanTest
{
    public static String decode(ByteBuffer buffer, int length) throws EncodingException
    {
        HuffmanDecoder huffmanDecoder = new HuffmanDecoder();
        huffmanDecoder.setLength(length);
        String decoded = huffmanDecoder.decode(buffer);
        if (decoded == null)
            throw new EncodingException("invalid string encoding");

        huffmanDecoder.reset();
        return decoded;
    }

    public static Stream<Arguments> data()
    {
        return Stream.of(
            new String[][]{
                {"D.4.1", "f1e3c2e5f23a6ba0ab90f4ff", "www.example.com"},
                {"D.4.2", "a8eb10649cbf", "no-cache"},
                {"D.6.1k", "6402", "302"},
                {"D.6.1v", "aec3771a4b", "private"},
                {"D.6.1d", "d07abe941054d444a8200595040b8166e082a62d1bff", "Mon, 21 Oct 2013 20:13:21 GMT"},
                {"D.6.1l", "9d29ad171863c78f0b97c8e9ae82ae43d3", "https://www.example.com"},
                {"D.6.2te", "640cff", "303"},
                }).map(Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] spec={0}")
    @MethodSource("data")
    public void testDecode(String specSection, String hex, String expected) throws Exception
    {
        byte[] encoded = TypeUtil.fromHexString(hex);
        HuffmanDecoder huffmanDecoder = new HuffmanDecoder();
        huffmanDecoder.setLength(encoded.length);
        String decoded = huffmanDecoder.decode(ByteBuffer.wrap(encoded));
        assertEquals(expected, decoded, specSection);
    }

    @ParameterizedTest(name = "[{index}] spec={0}")
    @MethodSource("data")
    public void testEncode(String specSection, String hex, String expected)
    {
        ByteBuffer buf = BufferUtil.allocate(1024);
        int pos = BufferUtil.flipToFill(buf);
        HuffmanEncoder.encode(buf, expected);
        BufferUtil.flipToFlush(buf, pos);
        String encoded = TypeUtil.toHexString(BufferUtil.toArray(buf)).toLowerCase(Locale.ENGLISH);
        assertEquals(hex, encoded, specSection);
        assertEquals(hex.length() / 2, HuffmanEncoder.octetsNeeded(expected));
    }

    public static Stream<Arguments> testDecode8859OnlyArguments()
    {
        return Stream.of(
            // These are valid characters for ISO-8859-1.
            Arguments.of("FfFe6f", (char)128),
            Arguments.of("FfFfFbBf", (char)255),

            // RFC9110 specifies these to be replaced as ' ' during decoding.
            Arguments.of("FfC7", ' '), // (char)0
            Arguments.of("FfFfFfF7", ' '), // '\r'
            Arguments.of("FfFfFfF3", ' '), // '\n'

            // We replace control chars with the default replacement character of '?'.
            Arguments.of("FfFfFfBf", '?') // (char)(' ' - 1)
        );
    }

    @ParameterizedTest(name = "[{index}]") // don't include unprintable character in test display-name
    @MethodSource("testDecode8859OnlyArguments")
    public void testDecode8859Only(String hexString, char expected) throws Exception
    {
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(hexString));
        String decoded = decode(buffer, buffer.remaining());
        assertThat(decoded, equalTo("" + expected));
    }

    public static Stream<Arguments> testEncode8859OnlyArguments()
    {
        return Stream.of(
            Arguments.of((char)128, (char)128),
            Arguments.of((char)255, (char)255),
            Arguments.of((char)0, null),
            Arguments.of('\r', null),
            Arguments.of('\n', null),
            Arguments.of((char)456, null),
            Arguments.of((char)256, null),
            Arguments.of((char)-1, null),
            Arguments.of((char)(' ' - 1), null)
        );
    }

    @ParameterizedTest(name = "[{index}]") // don't include unprintable character in test display-name
    @MethodSource("testEncode8859OnlyArguments")
    public void testEncode8859Only(char value, Character expectedValue) throws Exception
    {
        String s = "value = '" + value + "'";

        // If expected is null we should not be able to encode.
        if (expectedValue == null)
        {
            assertThat(HuffmanEncoder.octetsNeeded(s), equalTo(-1));
            assertThrows(Throwable.class, () -> encode(s));
            return;
        }

        String expected = "value = '" + expectedValue + "'";
        assertThat(HuffmanEncoder.octetsNeeded(s), greaterThan(0));
        ByteBuffer buffer = encode(s);
        String decode = decode(buffer);
        System.err.println("decoded: " + decode);
        assertThat(decode, equalTo(expected));
    }

    private ByteBuffer encode(String s)
    {
        ByteBuffer buffer = BufferUtil.allocate(32);
        BufferUtil.clearToFill(buffer);
        HuffmanEncoder.encode(buffer, s);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    private String decode(ByteBuffer buffer) throws Exception
    {
        return decode(buffer, buffer.remaining());
    }
}
