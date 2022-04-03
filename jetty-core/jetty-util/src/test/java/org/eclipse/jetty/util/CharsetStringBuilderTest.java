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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
        assertThat(builder.takeString(), equalTo(test));

        for (byte b : bytes)
            builder.append(b);
        assertThat(builder.takeString(), equalTo(test));

        builder.append(bytes[0]);
        builder.append(bytes, 1, bytes.length - 1);
        assertThat(builder.takeString(), equalTo(test));
    }
}
