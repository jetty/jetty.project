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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.test10.ExampleClass;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TypeUtilTest
{

    @Test
    public void convertHexDigitTest()
    {
        assertEquals((byte)0, TypeUtil.convertHexDigit((byte)'0'));
        assertEquals((byte)9, TypeUtil.convertHexDigit((byte)'9'));
        assertEquals((byte)10, TypeUtil.convertHexDigit((byte)'a'));
        assertEquals((byte)10, TypeUtil.convertHexDigit((byte)'A'));
        assertEquals((byte)15, TypeUtil.convertHexDigit((byte)'f'));
        assertEquals((byte)15, TypeUtil.convertHexDigit((byte)'F'));

        assertEquals(0, TypeUtil.convertHexDigit((int)'0'));
        assertEquals(9, TypeUtil.convertHexDigit((int)'9'));
        assertEquals(10, TypeUtil.convertHexDigit((int)'a'));
        assertEquals(10, TypeUtil.convertHexDigit((int)'A'));
        assertEquals(15, TypeUtil.convertHexDigit((int)'f'));
        assertEquals(15, TypeUtil.convertHexDigit((int)'F'));
    }

    @Test
    public void testToHexInt() throws Exception
    {
        StringBuilder b = new StringBuilder();

        b.setLength(0);
        TypeUtil.toHex(0, b);
        assertEquals("00000000", b.toString());

        b.setLength(0);
        TypeUtil.toHex(Integer.MAX_VALUE, b);
        assertEquals("7FFFFFFF", b.toString());

        b.setLength(0);
        TypeUtil.toHex(Integer.MIN_VALUE, b);
        assertEquals("80000000", b.toString());

        b.setLength(0);
        TypeUtil.toHex(0x12345678, b);
        assertEquals("12345678", b.toString());

        b.setLength(0);
        TypeUtil.toHex(0x9abcdef0, b);
        assertEquals("9ABCDEF0", b.toString());
    }

    @Test
    public void testToHexLong() throws Exception
    {
        StringBuilder b = new StringBuilder();

        b.setLength(0);
        TypeUtil.toHex((long)0, b);
        assertEquals("0000000000000000", b.toString());

        b.setLength(0);
        TypeUtil.toHex(Long.MAX_VALUE, b);
        assertEquals("7FFFFFFFFFFFFFFF", b.toString());

        b.setLength(0);
        TypeUtil.toHex(Long.MIN_VALUE, b);
        assertEquals("8000000000000000", b.toString());

        b.setLength(0);
        TypeUtil.toHex(0x123456789abcdef0L, b);
        assertEquals("123456789ABCDEF0", b.toString());
    }

    public static Stream<Arguments> isHexTrueSource()
    {
        return Stream.of(
            Arguments.of("2A", 0, 2),
            Arguments.of("2a", 0, 2),
            Arguments.of("0x2F", 2, 2),
            Arguments.of("0x2f", 2, 2),
            Arguments.of("%25", 1, 2),
            Arguments.of("%0d", 1, 2),
            Arguments.of("%uC0AC", 2, 4),
            Arguments.of("%uc0ac", 2, 4)
        );
    }

    @ParameterizedTest
    @MethodSource("isHexTrueSource")
    public void testIsHexTrue(String input, int offset, int length)
    {
        assertTrue(TypeUtil.isHex(input, offset, length));
    }

    public static Stream<Arguments> isHexFalseSource()
    {
        return Stream.of(
            Arguments.of("gg", 0, 2),
            Arguments.of("GG", 0, 2),
            Arguments.of("0xZZ", 2, 2),
            Arguments.of("0xyz", 2, 2),
            Arguments.of("%xy", 1, 2),
            Arguments.of("%0z", 1, 2),
            Arguments.of("%users", 2, 4)
        );
    }

    @ParameterizedTest
    @MethodSource("isHexFalseSource")
    public void testIsHexFalse(String input, int offset, int length)
    {
        assertFalse(TypeUtil.isHex(input, offset, length));
    }

    @Test
    public void testIsTrue()
    {
        assertTrue(TypeUtil.isTrue(Boolean.TRUE));
        assertTrue(TypeUtil.isTrue(true));
        assertTrue(TypeUtil.isTrue("true"));
        assertTrue(TypeUtil.isTrue(new Object()
        {
            @Override
            public String toString()
            {
                return "true";
            }
        }));

        assertFalse(TypeUtil.isTrue(Boolean.FALSE));
        assertFalse(TypeUtil.isTrue(false));
        assertFalse(TypeUtil.isTrue("false"));
        assertFalse(TypeUtil.isTrue("blargle"));
        assertFalse(TypeUtil.isTrue(new Object()
        {
            @Override
            public String toString()
            {
                return "false";
            }
        }));
    }

    @Test
    public void testIsFalse()
    {
        assertTrue(TypeUtil.isFalse(Boolean.FALSE));
        assertTrue(TypeUtil.isFalse(false));
        assertTrue(TypeUtil.isFalse("false"));
        assertTrue(TypeUtil.isFalse(new Object()
        {
            @Override
            public String toString()
            {
                return "false";
            }
        }));

        assertFalse(TypeUtil.isFalse(Boolean.TRUE));
        assertFalse(TypeUtil.isFalse(true));
        assertFalse(TypeUtil.isFalse("true"));
        assertFalse(TypeUtil.isFalse("blargle"));
        assertFalse(TypeUtil.isFalse(new Object()
        {
            @Override
            public String toString()
            {
                return "true";
            }
        }));
    }

    @Test
    public void testGetLocationOfClassFromMavenRepo()
    {
        String mavenRepoPathProperty = System.getProperty("mavenRepoPath");
        assumeTrue(mavenRepoPathProperty != null);
        Path mavenRepoPath = Paths.get(mavenRepoPathProperty);

        // Classes from maven dependencies
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(Objects.requireNonNull(TypeUtil.getLocationOfClass(Assertions.class)).toASCIIString());
            assertThat(resource.getPath().toString(), Matchers.startsWith(mavenRepoPath.toString()));
        }
    }

    @Test
    public void getLocationOfClassClassDirectory()
    {
        // Class from project dependencies
        assertThat(Objects.requireNonNull(TypeUtil.getLocationOfClass(TypeUtil.class)).toASCIIString(), containsString("/classes/"));
    }

    @Test
    public void testGetLocationJvmCoreJPMS()
    {
        // Class from JVM core
        String expectedJavaBase = "/java.base";
        assertThat(Objects.requireNonNull(TypeUtil.getLocationOfClass(String.class)).toASCIIString(), containsString(expectedJavaBase));
    }

    @Test
    public void testGetLocationJavaLangThreadDeathJPMS()
    {
        // Class from JVM core
        String expectedJavaBase = "/java.base";
        assertThat(Objects.requireNonNull(TypeUtil.getLocationOfClass(ThreadDeath.class)).toASCIIString(), containsString(expectedJavaBase));
    }

    public static Stream<Arguments> shortNames()
    {
        return Stream.of(
            Arguments.of(TypeUtilTest.class, "oeju.TypeUtilTest"),
            Arguments.of(ExampleClass.class, "oejut10.ExampleClass"),
            // example of inner type
            Arguments.of(Attributes.Wrapper.class, "oeju.Attributes$Wrapper")
        );
    }

    @ParameterizedTest
    @MethodSource("shortNames")
    public void testToShortName(Class<?> clazz, String shortName)
    {
        assertThat(TypeUtil.toShortName(clazz), is(shortName));
    }

    public static class Base
    {
        protected String methodA(String arg)
        {
            return "a" + arg.length();
        }

        protected String methodB(String arg)
        {
            return "b" + arg.length();
        }
    }

    public static class Example extends Base
    {
        @Override
        protected String methodB(String arg)
        {
            return "B" + arg;
        }
    }

    @Test
    public void testIsMethodDeclaredOn()
    {
        Example example = new Example();
        assertFalse(TypeUtil.isDeclaredMethodOn(example, "methodA", String.class));
        assertTrue(TypeUtil.isDeclaredMethodOn(example, "methodB", String.class));
    }

    public static Stream<Arguments> offsetLengthSizeExpected()
    {
        return Stream.of(
            Arguments.of(0L, 0L, 0L, 0L),
            Arguments.of(0L, 0L, 10L, 0L),
            Arguments.of(0L, 10L, 10L, 10L),
            Arguments.of(5L, 5L, 10L, 5L),
            Arguments.of(5L, 5L, 15L, 5L),
            Arguments.of(5L, 10L, 15L, 10L),
            Arguments.of(0L, 10L, -1L, 10L),
            Arguments.of(5L, 10L, -1L, 10L),
            Arguments.of(5L, -1L, 15L, 10L),
            Arguments.of(0L, -1L, -1L, -1L),
            Arguments.of(5L, -1L, -1L, -1L),
            Arguments.of(1L, 0L, 0L, 0L),
            Arguments.of(0L, 1L, 0L, 0L),
            Arguments.of(1L, 1L, 0L, -2L),
            Arguments.of(10L, 0L, 10L, 0L),
            Arguments.of(0L, 11L, 10L, 10L),
            Arguments.of(5L, 6L, 10L, 5L)
        );
    }

    @ParameterizedTest
    @MethodSource("offsetLengthSizeExpected")
    public void testCheckOffsetLengthSize(long offset, long length, long size, long expected)
    {
        if (expected < -1)
            assertThrows(IndexOutOfBoundsException.class, () -> TypeUtil.checkOffsetLengthSize(offset, length, size));
        else
            assertThat(TypeUtil.checkOffsetLengthSize(offset, length, size), is(expected));
    }
}
