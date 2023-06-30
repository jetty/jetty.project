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

package org.eclipse.jetty.server;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FormFieldsTest
{
    public static Stream<Arguments> tests()
    {
        return Stream.of(
            Arguments.of(List.of("name=value"), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("name=value", ""), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("name", "=value", ""), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("n", "ame", "=", "value"), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, 2, 4, Map.of("n", "v", "X", "Y")),
            Arguments.of(List.of("name=f¤¤&X=Y"), UTF_8, -1, -1, Map.of("name", "f¤¤", "X", "Y")),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, 1, -1, null),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, -1, 3, null)
        );
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void testFormFields(List<String> chunks, Charset charset, int maxFields, int maxLength, Map<String, String> expected)
        throws Exception
    {
        AsyncContent source = new AsyncContent();
        Attributes attributes = new Attributes.Mapped();
        CompletableFuture<Fields> futureFields = FormFields.from(source, attributes, charset, maxFields, maxLength);
        assertFalse(futureFields.isDone());

        int last = chunks.size() - 1;
        FutureCallback eof = new FutureCallback();
        for (int i = 0; i <= last; i++)
            source.write(i == last, BufferUtil.toBuffer(chunks.get(i), charset), i == last ? eof : Callback.NOOP);


        try
        {
            eof.get(10, TimeUnit.SECONDS);
            assertTrue(futureFields.isDone());

            Map<String, String> result = new HashMap<>();
            for (Fields.Field f : futureFields.get())
                result.put(f.getName(), f.getValue());

            assertEquals(expected, result);
        }
        catch (AssertionError e)
        {
            throw e;
        }
        catch (Throwable e)
        {
            assertNull(expected);
        }
    }
}
