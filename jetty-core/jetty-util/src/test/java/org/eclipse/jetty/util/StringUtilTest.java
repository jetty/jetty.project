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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class StringUtilTest
{

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testAsciiToLowerCase()
    {
        String lc = "\u0690bc def 1\u06903";
        assertEquals(StringUtil.asciiToLowerCase("\u0690Bc DeF 1\u06903"), lc);
        assertTrue(StringUtil.asciiToLowerCase(lc) == lc);
    }

    @Test
    public void testStartsWithIgnoreCase()
    {

        assertTrue(StringUtil.startsWithIgnoreCase("\u0690b\u0690defg", "\u0690b\u0690"));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690bcdefg", "\u0690bc"));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690bcdefg", "\u0690Bc"));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690Bcdefg", "\u0690bc"));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690Bcdefg", "\u0690Bc"));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690bcdefg", ""));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690bcdefg", null));
        assertTrue(StringUtil.startsWithIgnoreCase("\u0690bcdefg", "\u0690bcdefg"));

        assertFalse(StringUtil.startsWithIgnoreCase(null, "xyz"));
        assertFalse(StringUtil.startsWithIgnoreCase("\u0690bcdefg", "xyz"));
        assertFalse(StringUtil.startsWithIgnoreCase("\u0690", "xyz"));
    }

    @Test
    public void testEndsWithIgnoreCase()
    {
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcd\u0690f\u0690", "\u0690f\u0690"));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdefg", "efg"));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdefg", "eFg"));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdeFg", "efg"));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdeFg", "eFg"));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdefg", ""));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdefg", null));
        assertTrue(StringUtil.endsWithIgnoreCase("\u0690bcdefg", "\u0690bcdefg"));

        assertFalse(StringUtil.endsWithIgnoreCase(null, "xyz"));
        assertFalse(StringUtil.endsWithIgnoreCase("\u0690bcdefg", "xyz"));
        assertFalse(StringUtil.endsWithIgnoreCase("\u0690", "xyz"));
    }

    @Test
    public void testIndexFrom()
    {
        assertEquals(StringUtil.indexFrom("\u0690bcd", "xyz"), -1);
        assertEquals(StringUtil.indexFrom("\u0690bcd", "\u0690bcz"), 0);
        assertEquals(StringUtil.indexFrom("\u0690bcd", "bcz"), 1);
        assertEquals(StringUtil.indexFrom("\u0690bcd", "dxy"), 3);
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testReplace()
    {
        String s = "\u0690bc \u0690bc \u0690bc";
        assertEquals(StringUtil.replace(s, "\u0690bc", "xyz"), "xyz xyz xyz");
        assertTrue(StringUtil.replace(s, "xyz", "pqy") == s);

        s = " \u0690bc ";
        assertEquals(StringUtil.replace(s, "\u0690bc", "xyz"), " xyz ");
    }

    public static Stream<Arguments> replaceFirstArgs()
    {
        return Stream.of(
            // no match
            Arguments.of("abc", "z", "foo", "abc"),

            // matches at start of string
            Arguments.of("abc", "a", "foo", "foobc"),
            Arguments.of("abcabcabc", "a", "foo", "foobcabcabc"),

            // matches in middle of string
            Arguments.of("abc", "b", "foo", "afooc"),
            Arguments.of("abcabcabc", "b", "foo", "afoocabcabc"),
            Arguments.of("abcabcabc", "cab", "X", "abXcabc"),

            // matches at end of string
            Arguments.of("abc", "c", "foo", "abfoo")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "replaceFirstArgs")
    public void testReplaceFirst(String original, String target, String replacement, String expected)
    {
        assertThat(StringUtil.replaceFirst(original, target, replacement), is(expected));
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testNonNull()
    {
        String nn = "non empty string";
        assertThat(StringUtil.nonNull(nn), sameInstance(nn));
        assertEquals("", StringUtil.nonNull(null));
    }

    /*
     * Test for boolean equals(String, char[], int, int)
     */
    @Test
    public void testEqualsStringcharArrayintint()
    {
        assertTrue(StringUtil.equals("\u0690bc", new char[]{'x', '\u0690', 'b', 'c', 'z'}, 1, 3));
        assertFalse(StringUtil.equals("axc", new char[]{'x', 'a', 'b', 'c', 'z'}, 1, 3));
    }

    @Test
    public void testAppend()
    {
        StringBuilder buf = new StringBuilder();
        buf.append('a');
        StringUtil.append(buf, "abc", 1, 1);
        StringUtil.append(buf, (byte)12, 16);
        StringUtil.append(buf, (byte)16, 16);
        StringUtil.append(buf, (byte)-1, 16);
        StringUtil.append(buf, (byte)-16, 16);
        assertEquals("ab0c10fff0", buf.toString());
    }

    @Test
    public void testHasControlCharacter()
    {
        assertThat(StringUtil.indexOfControlChars("\r\n"), is(0));
        assertThat(StringUtil.indexOfControlChars("\t"), is(0));
        assertThat(StringUtil.indexOfControlChars(";\n"), is(1));
        assertThat(StringUtil.indexOfControlChars("abc\fz"), is(3));
        //@checkstyle-disable-check : IllegalTokenText
        assertThat(StringUtil.indexOfControlChars("z\010"), is(1));
        //@checkstyle-enable-check : IllegalTokenText
        assertThat(StringUtil.indexOfControlChars(":\u001c"), is(1));

        assertThat(StringUtil.indexOfControlChars(null), is(-1));
        assertThat(StringUtil.indexOfControlChars(""), is(-1));
        assertThat(StringUtil.indexOfControlChars("   "), is(-1));
        assertThat(StringUtil.indexOfControlChars("a"), is(-1));
        assertThat(StringUtil.indexOfControlChars("."), is(-1));
        assertThat(StringUtil.indexOfControlChars(";"), is(-1));
        assertThat(StringUtil.indexOfControlChars("Euro is \u20ac"), is(-1));
    }

    @Test
    public void testIsBlank()
    {
        assertTrue(StringUtil.isBlank(null));
        assertTrue(StringUtil.isBlank(""));
        assertTrue(StringUtil.isBlank("\r\n"));
        assertTrue(StringUtil.isBlank("\t"));
        assertTrue(StringUtil.isBlank("   "));

        assertFalse(StringUtil.isBlank("a"));
        assertFalse(StringUtil.isBlank("  a"));
        assertFalse(StringUtil.isBlank("a  "));
        assertFalse(StringUtil.isBlank("."));
        assertFalse(StringUtil.isBlank(";\n"));
    }

    @Test
    public void testIsNotBlank()
    {
        assertFalse(StringUtil.isNotBlank(null));
        assertFalse(StringUtil.isNotBlank(""));
        assertFalse(StringUtil.isNotBlank("\r\n"));
        assertFalse(StringUtil.isNotBlank("\t"));
        assertFalse(StringUtil.isNotBlank("   "));

        assertTrue(StringUtil.isNotBlank("a"));
        assertTrue(StringUtil.isNotBlank("  a"));
        assertTrue(StringUtil.isNotBlank("a  "));
        assertTrue(StringUtil.isNotBlank("."));
        assertTrue(StringUtil.isNotBlank(";\n"));
    }

    @Test
    public void testIsEmpty()
    {
        assertTrue(StringUtil.isEmpty(null));
        assertTrue(StringUtil.isEmpty(""));
        assertFalse(StringUtil.isEmpty("\r\n"));
        assertFalse(StringUtil.isEmpty("\t"));
        assertFalse(StringUtil.isEmpty("   "));

        assertFalse(StringUtil.isEmpty("a"));
        assertFalse(StringUtil.isEmpty("  a"));
        assertFalse(StringUtil.isEmpty("a  "));
        assertFalse(StringUtil.isEmpty("."));
        assertFalse(StringUtil.isEmpty(";\n"));
    }

    @Test
    public void testSanitizeHTML()
    {
        assertEquals(null, StringUtil.sanitizeXmlString(null));
        assertEquals("", StringUtil.sanitizeXmlString(""));
        assertEquals("&lt;&amp;&gt;", StringUtil.sanitizeXmlString("<&>"));
        assertEquals("Hello &lt;Cruel&gt; World", StringUtil.sanitizeXmlString("Hello <Cruel> World"));
        assertEquals("Hello ? World", StringUtil.sanitizeXmlString("Hello \u0000 World"));
    }

    @Test
    public void testSplit()
    {
        assertThat(StringUtil.csvSplit(null), nullValue());
        assertThat(StringUtil.csvSplit(null), nullValue());

        assertThat(StringUtil.csvSplit(""), emptyArray());
        assertThat(StringUtil.csvSplit(" \t\n"), emptyArray());

        assertThat(StringUtil.csvSplit("aaa"), arrayContaining("aaa"));
        assertThat(StringUtil.csvSplit(" \taaa\n"), arrayContaining("aaa"));
        assertThat(StringUtil.csvSplit(" \ta\n"), arrayContaining("a"));
        assertThat(StringUtil.csvSplit(" \t\u1234\n"), arrayContaining("\u1234"));

        assertThat(StringUtil.csvSplit("aaa,bbb,ccc"), arrayContaining("aaa", "bbb", "ccc"));
        assertThat(StringUtil.csvSplit("aaa,,ccc"), arrayContaining("aaa", "", "ccc"));
        assertThat(StringUtil.csvSplit(",b b,"), arrayContaining("", "b b"));
        assertThat(StringUtil.csvSplit(",,bbb,,"), arrayContaining("", "", "bbb", ""));

        assertThat(StringUtil.csvSplit(" aaa, bbb, ccc"), arrayContaining("aaa", "bbb", "ccc"));
        assertThat(StringUtil.csvSplit("aaa,\t,ccc"), arrayContaining("aaa", "", "ccc"));
        assertThat(StringUtil.csvSplit("  ,  b b  ,   "), arrayContaining("", "b b"));
        assertThat(StringUtil.csvSplit(" ,\n,bbb, , "), arrayContaining("", "", "bbb", ""));

        assertThat(StringUtil.csvSplit("\"aaa\", \" b,\\\"\",\"\""), arrayContaining("aaa", " b,\"", ""));
    }

    @Test
    public void testFromHexStringGood()
    {
        assertArrayEquals(new byte[]{0x12, 0x34, 0x56, 0x78, (byte)0x9A}, StringUtil.fromHexString("123456789A"));
    }

    @Test
    public void testFromHexStringBad()
    {
        assertThrows(NumberFormatException.class, () -> StringUtil.fromHexString("Hello World "));
    }

    @Test
    public void testToHexStringGood()
    {
        assertThat(StringUtil.toHexString(new byte[]{0x12, 0x34, 0x56, 0x78, (byte)0x9A}), is("123456789a"));
    }

    @Test
    public void testToHexStringNull()
    {
        assertThrows(NullPointerException.class, () -> StringUtil.toHexString(null));
    }

    @Test
    public void testToHexStringEmpty()
    {
        assertThat(StringUtil.toHexString(new byte[0]), is(""));
    }

    public static Stream<Arguments> subSequenceTests()
    {
        return Stream.of(
            Arguments.of("", 0, 0, ""),
            Arguments.of("", 0, 1, null),
            Arguments.of("", 1, 0, ""),
            Arguments.of("", 1, 1, null),
            Arguments.of("hello", 0, 5, "hello"),
            Arguments.of("hello", 0, 4, "hell"),
            Arguments.of("hello", 1, 4, "ello"),
            Arguments.of("hello", 1, 3, "ell"),
            Arguments.of("hello", 5, 0, ""),
            Arguments.of("hello", 0, 6, null)
        );
    }

    @ParameterizedTest
    @MethodSource("subSequenceTests")
    public void testSubSequence(String source, int offset, int length, String expected)
    {
        if (expected == null)
        {
            assertThrows(IndexOutOfBoundsException.class, () -> StringUtil.subSequence(source, offset, length));
            assertThrows(IndexOutOfBoundsException.class, () -> StringUtil.subSequence(source.toCharArray(), offset, length));
            return;
        }

        CharSequence result = StringUtil.subSequence(source, offset, length);
        assertThat(result.toString(), equalTo(expected));

        // check string optimization
        if (offset == 0 && length == source.length())
        {
            assertThat(result, sameInstance(source));
            assertThat(result.subSequence(offset, length), sameInstance(source));
            return;
        }

        result = StringUtil.subSequence(source.toCharArray(), offset, length);
        assertThat(result.toString(), equalTo(expected));
    }
}
