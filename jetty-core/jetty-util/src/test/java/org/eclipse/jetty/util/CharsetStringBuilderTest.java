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
import java.nio.charset.Charset;
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
            builder.append(b);
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
    public void testBasicApi(Charset charset) throws Exception
    {
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        ByteBuffer encoded = charset.encode("1");
        while (encoded.hasRemaining())
            builder.append(encoded.get());

        builder.append('2');

        builder.append(charset.encode("34"));

        encoded = charset.encode("abc");
        int offset = encoded.remaining();
        encoded = charset.encode("abc56");
        int length = encoded.remaining() - offset;
        encoded = charset.encode("abc56xyz");
        byte[] bytes = new byte[1028];
        encoded.get(bytes, 0, encoded.remaining());
        builder.append(bytes, offset, length);

        encoded = charset.encode("abc78xyz");
        encoded.position(offset);
        encoded.limit(offset + length);
        builder.append(encoded);

        builder.append("9A", 0, 2);
        builder.append("xyzBCpqy", 3, 2);

        assertThat(builder.build(), is("123456789ABC"));
    }
}
