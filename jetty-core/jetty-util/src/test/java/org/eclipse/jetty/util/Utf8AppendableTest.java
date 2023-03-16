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

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class Utf8AppendableTest
{
    public static Stream<Class<? extends Utf8Appendable>> implementations()
    {
        return Stream.of(Utf8StringBuilder.class, Utf8StringBuffer.class);
    }

    public static Stream<Arguments> implementationArgs()
    {
        return implementations().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testUtf(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "abcd012345\n\r\u0000¤჻\ufffdjetty";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (byte aByte : bytes)
        {
            buffer.append(aByte);
        }
        assertEquals(source, buffer.toString());
        assertTrue(buffer.toString().endsWith("jetty"));
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testUtf8WithMissingByte(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "abc჻";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (int i = 0; i < bytes.length - 1; i++)
        {
            buffer.append(bytes[i]);
        }
        assertThat(buffer.toString(), equalTo("abc"));
        assertThat(buffer.toCompleteString(), equalTo("abc�"));
        assertThrows(CharacterCodingException.class, buffer::takeString);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testUtf8WithAdditionalByte(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "abcXX";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        bytes[3] = (byte)0xc0;
        bytes[4] = (byte)0x00;

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (byte aByte : bytes)
            buffer.append(aByte);

        assertThat(buffer.toString(), equalTo("abc�\000"));
        assertThat(buffer.toCompleteString(), equalTo("abc�\000"));
        assertThrows(CharacterCodingException.class, buffer::takeString);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testUTF32codes(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "\uD842\uDF9F";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        String jvmcheck = new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);
        assertEquals(source, jvmcheck);

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        buffer.append(bytes, 0, bytes.length);
        String result = buffer.toString();
        assertEquals(source, result);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testGermanUmlauts(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] bytes = new byte[6];
        bytes[0] = (byte)0xC3;
        bytes[1] = (byte)0xBC;
        bytes[2] = (byte)0xC3;
        bytes[3] = (byte)0xB6;
        bytes[4] = (byte)0xC3;
        bytes[5] = (byte)0xA4;

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        for (byte aByte : bytes)
            buffer.append(aByte);

        assertEquals("üöä", buffer.toString());
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testInvalidUTF8(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        buffer.append((byte)0xC2);
        buffer.append((byte)0xC2);
        assertThat(buffer.toString(), equalTo("�"));
        assertThat(buffer.toCompleteString(), equalTo("�"));
        assertThrows(CharacterCodingException.class, buffer::takeString);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testInvalidZeroUTF8(Class<Utf8Appendable> impl) throws Exception
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        buffer.append((byte)0xC0);
        buffer.append((byte)0x80);
        assertThat(buffer.toString(), equalTo("��"));
        assertThat(buffer.toCompleteString(), equalTo("��"));
        assertThrows(CharacterCodingException.class, buffer::takeString);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testInvalidAlternateDotEncodingUTF8(Class<Utf8Appendable> impl) throws Exception
    {
        // From https://datatracker.ietf.org/doc/html/rfc3629#section-10
        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        buffer.append((byte)0x2f);
        buffer.append((byte)0xc0);
        buffer.append((byte)0xae);
        buffer.append((byte)0x2e);
        buffer.append((byte)0x2f);

        assertThat(buffer.toString(), equalTo("/��./"));
        assertThat(buffer.toCompleteString(), equalTo("/��./"));
        assertThrows(CharacterCodingException.class, buffer::takeString);
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testFastFail1(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5");
        byte[] part2 = StringUtil.fromHexString("f4908080"); // INVALID
        // Here for test tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);
        assertFalse(buffer.hasCodingErrors());

        // Part 2 is invalid
        buffer.append(part2, 0, part2.length);
        assertTrue(buffer.hasCodingErrors());
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testFastFail2(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = StringUtil.fromHexString("cebae1bdb9cf83cebcceb5f4");
        byte[] part2 = StringUtil.fromHexString("90"); // INVALID
        // Here for test search/tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("8080656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);
        assertFalse(buffer.hasCodingErrors());

        // Part 2 is invalid
        buffer.append(part2, 0, part2.length);
        assertTrue(buffer.hasCodingErrors());
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testPartialUnsplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "Hello-습@쎟쎤";
        String seq2 = "쎼쎠쎡-UTF-8!!";

        utf8.append(BufferUtil.toBuffer(seq1, StandardCharsets.UTF_8));
        String partial1 = utf8.toString();
        utf8.partialReset();
        String ret1 = partial1;

        utf8.append(BufferUtil.toBuffer(seq2, StandardCharsets.UTF_8));
        String partial = utf8.toString();
        utf8.partialReset();
        String ret2 = partial;

        assertThat("Seq1", ret1, is(seq1));
        assertThat("Seq2", ret2, is(seq2));
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testPartialSplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String partial1 = utf8.toString();
        utf8.partialReset();
        String ret1 = partial1;

        utf8.append(StringUtil.fromHexString(seq2));
        String partial = utf8.toString();
        utf8.partialReset();
        String ret2 = partial;

        assertThat("Seq1", ret1, is("Hello-습@쎟"));
        assertThat("Seq2", ret2, is("쎤쎼쎠쎡-UTF-8!!"));
    }

    @ParameterizedTest
    @MethodSource("implementationArgs")
    public void testPartialSplitCodepointWithNoBuf(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(StringUtil.fromHexString(seq1));
        String partial2 = utf8.toString();
        utf8.partialReset();
        String ret1 = partial2;
        String partial1 = utf8.toString();
        utf8.partialReset();
        String ret2 = partial1;
        utf8.append(StringUtil.fromHexString(seq2));
        String partial = utf8.toString();
        utf8.partialReset();
        String ret3 = partial;

        assertThat("Seq1", ret1, is("Hello-습@쎟"));
        assertThat("Seq2", ret2, is(""));
        assertThat("Seq3", ret3, is("쎤쎼쎠쎡-UTF-8!!"));
    }

    @TestFactory
    public Iterator<DynamicTest> testBadUtf8()
    {
        String[] samples = new String[]{
            "c0af",
            "EDA080",
            "f08080af",
            "f8808080af",
            "e080af",
            "F4908080",
            "fbbfbfbfbf",
            "10FFFF",
            "CeBaE1BdB9Cf83CeBcCeB5EdA080656469746564",
            // use of UTF-16 High Surrogates (in codepoint form)
            "da07",
            "d807",
            // decoded UTF-16 High Surrogate "\ud807" (in UTF-8 form)
            "EDA087"
        };

        List<DynamicTest> tests = new ArrayList<>();

        implementations().forEach(impl ->
        {
            for (String hex : samples)
            {
                tests.add(dynamicTest(impl.getSimpleName() + " : " + hex, () ->
                {
                    Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();
                    utf8.append(StringUtil.fromHexString(hex));

                    assertThat(utf8.toString(), containsString("�"));
                    assertThat(utf8.toCompleteString(), containsString("�"));
                    assertThrows(CharacterCodingException.class, utf8::takeString);
                }));
            }
        });

        return tests.iterator();
    }
}
