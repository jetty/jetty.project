//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UrlEncodedInvalidEncodingTest
{
    public static Stream<Arguments> data()
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
    @MethodSource("data")
    public void testDecode(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            UrlEncoded urlEncoded = new UrlEncoded();
            urlEncoded.decode(inputString, charset);
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDecodeUtf8ToMap(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            MultiMap<String> map = new MultiMap<>();
            UrlEncoded.decodeUtf8To(inputString, map);
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDecodeTo(String inputString, Charset charset, Class<? extends Throwable> expectedThrowable)
    {
        assertThrows(expectedThrowable, () ->
        {
            MultiMap<String> map = new MultiMap<>();
            UrlEncoded.decodeTo(inputString, map, charset);
        });
    }
}
