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

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FormFieldsTest
{
    public static Stream<Arguments> validData()
    {
        return Stream.of(
            Arguments.of(List.of(""), UTF_8, -1, -1, Map.of()),
            Arguments.of(List.of("name"), UTF_8, -1, -1, Map.of("name", "")),
            Arguments.of(List.of("name&"), UTF_8, -1, -1, Map.of("name", "")),
            Arguments.of(List.of("name="), UTF_8, -1, -1, Map.of("name", "")),
            Arguments.of(List.of("name%00="), UTF_8, -1, -1, Map.of("name\u0000", "")),
            Arguments.of(List.of("n1=v1&n2"), UTF_8, -1, -1, Map.of("n1", "v1", "n2", "")),
            Arguments.of(List.of("n1=v1&n2&n3=v3&n4"), UTF_8, -1, -1, Map.of("n1", "v1", "n2", "", "n3", "v3", "n4", "")),
            Arguments.of(List.of("name=value"), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("name=%0A"), UTF_8, -1, -1, Map.of("name", "\n")),
            Arguments.of(List.of("name=value", ""), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("name", "=value", ""), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("n", "ame", "=", "value"), UTF_8, -1, -1, Map.of("name", "value")),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, 2, 7, Map.of("n", "v", "X", "Y")),
            Arguments.of(List.of("name=f¤¤&X=Y"), UTF_8, -1, -1, Map.of("name", "f¤¤", "X", "Y")),
            Arguments.of(List.of("na+me=", "va", "+", "lue"), UTF_8, -1, -1, Map.of("na me", "va lue")),
            Arguments.of(List.of("=v"), UTF_8, -1, -1, Map.of("", "v")),
            Arguments.of(List.of("n1=v1&n2&n3=v3&n4"), UTF_8, 4, -1, Map.of("n1", "v1", "n2", "", "n3", "v3", "n4", ""))
        );
    }

    @ParameterizedTest
    @MethodSource("validData")
    public void testValidFormFields(List<String> chunks, Charset charset, int maxFields, int maxLength, Map<String, String> expected)
    {
        AsyncContent source = new AsyncContent();
        Attributes attributes = new Attributes.Mapped();
        CompletableFuture<Fields> futureFields = FormFields.from(source, Invocable.InvocationType.NON_BLOCKING, attributes, charset, maxFields, maxLength);
        assertFalse(futureFields.isDone());

        int last = chunks.size() - 1;
        FutureCallback eof = new FutureCallback();
        for (int i = 0; i <= last; i++)
        {
            source.write(i == last, BufferUtil.toBuffer(chunks.get(i), charset), i == last ? eof : Callback.NOOP);
        }

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

    public static Stream<Arguments> invalidData()
    {
        return Stream.of(
            Arguments.of(List.of("%A"), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("name%"), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("name%A"), UTF_8, -1, -1, IllegalStateException.class),

            Arguments.of(List.of("name%A="), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("name%A&"), UTF_8, -1, -1, IllegalArgumentException.class),

            Arguments.of(List.of("name=%"), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("name=A%%A"), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("name=A%%3D"), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("%="), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("name=%A"), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("name=value%A"), UTF_8, -1, -1, IllegalStateException.class),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, 1, -1, IllegalStateException.class),
            Arguments.of(List.of("n=v&X=Y"), UTF_8, -1, 3, IllegalStateException.class),
            Arguments.of(List.of("n%AH=v"), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("n=v%AH"), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("n=%%TOK%%"), UTF_8, -1, -1, IllegalArgumentException.class),
            Arguments.of(List.of("n=v%FF"), UTF_8, -1, -1, CharacterCodingException.class),
            Arguments.of(List.of("a=0&b=1&c=2&d=4"), UTF_8, 2, -1, IllegalStateException.class),
            Arguments.of(List.of("a=0", "&b=1&", "c=", "2&d=4"), UTF_8, 2, -1, IllegalStateException.class),
            Arguments.of(List.of("abcde=123456"), UTF_8, -1, 10, IllegalStateException.class),
            Arguments.of(List.of("abc=1234", "def=567"), UTF_8, -1, 10, IllegalStateException.class),
            Arguments.of(List.of("abcefghijklmnop="), UTF_8, -1, 10, IllegalStateException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidData")
    public void testInvalidFormFields(List<String> chunks, Charset charset, int maxFields, int maxLength, Class<? extends Exception> expectedException)
    {
        AsyncContent source = new AsyncContent();
        CompletableFuture<Fields> futureFields = FormFields.from(source, Invocable.InvocationType.NON_BLOCKING, new Attributes.Mapped(), charset, maxFields, maxLength);
        assertFalse(futureFields.isDone());
        int last = chunks.size() - 1;
        for (int i = 0; i <= last; i++)
        {
            source.write(i == last, BufferUtil.toBuffer(chunks.get(i)), Callback.NOOP);
        }
        Throwable cause = assertThrows(ExecutionException.class, futureFields::get).getCause();
        assertThat(cause, instanceOf(expectedException));
    }
}
