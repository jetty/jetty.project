//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.hpack;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HuffmanTest
{
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
        String decoded = Huffman.decode(ByteBuffer.wrap(encoded));
        assertEquals(expected, decoded, specSection);
    }

    @ParameterizedTest(name = "[{index}] spec={0}")
    @MethodSource("data")
    public void testEncode(String specSection, String hex, String expected)
    {
        ByteBuffer buf = BufferUtil.allocate(1024);
        int pos = BufferUtil.flipToFill(buf);
        Huffman.encode(buf, expected);
        BufferUtil.flipToFlush(buf, pos);
        String encoded = TypeUtil.toHexString(BufferUtil.toArray(buf)).toLowerCase(Locale.ENGLISH);
        assertEquals(hex, encoded, specSection);
        assertEquals(hex.length() / 2, Huffman.octetsNeeded(expected));
    }

    @ParameterizedTest(name = "[{index}]") // don't include unprintable character in test display-name
    @ValueSource(chars = {(char)128, (char)0, (char)-1, ' ' - 1})
    public void testEncode8859Only(char bad)
    {
        String s = "bad '" + bad + "'";

        assertThat(Huffman.octetsNeeded(s), Matchers.is(-1));

        assertThrows(BufferOverflowException.class,
            () -> Huffman.encode(BufferUtil.allocate(32), s));
    }
}
