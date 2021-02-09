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

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class Utf8AppendableTest
{
    public static final List<Class<? extends Utf8Appendable>> APPENDABLE_IMPLS;

    static
    {
        APPENDABLE_IMPLS = new ArrayList<>();
        APPENDABLE_IMPLS.add(Utf8StringBuilder.class);
        APPENDABLE_IMPLS.add(Utf8StringBuffer.class);
    }

    public static Stream<Arguments> implementations()
    {
        return APPENDABLE_IMPLS.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testUtf(Class<Utf8Appendable> impl) throws Exception
    {
        String source = "abcd012345\n\r\u0000\u00a4\u10fb\ufffdjetty";
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
    @MethodSource("implementations")
    public void testUtf8WithMissingByte(Class<Utf8Appendable> impl) throws Exception
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String source = "abc\u10fb";
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            for (int i = 0; i < bytes.length - 1; i++)
            {
                buffer.append(bytes[i]);
            }
            buffer.toString();
        });
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testUtf8WithAdditionalByte(Class<Utf8Appendable> impl) throws Exception
    {
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            String source = "abcXX";
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            bytes[3] = (byte)0xc0;
            bytes[4] = (byte)0x00;

            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            for (byte aByte : bytes)
            {
                buffer.append(aByte);
            }
        });
    }

    @ParameterizedTest
    @MethodSource("implementations")
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
    @MethodSource("implementations")
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
        for (int i = 0; i < bytes.length; i++)
        {
            buffer.append(bytes[i]);
        }

        assertEquals("\u00FC\u00F6\u00E4", buffer.toString());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testInvalidUTF8(Class<Utf8Appendable> impl) throws UnsupportedEncodingException
    {
        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
            buffer.append((byte)0xC2);
            buffer.append((byte)0xC2);
        });
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testFastFail1(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5");
        byte[] part2 = TypeUtil.fromHexString("f4908080"); // INVALID
        // Here for test tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);

        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            // Part 2 is invalid
            buffer.append(part2, 0, part2.length);
        });
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testFastFail2(Class<Utf8Appendable> impl) throws Exception
    {
        byte[] part1 = TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5f4");
        byte[] part2 = TypeUtil.fromHexString("90"); // INVALID
        // Here for test search/tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("8080656469746564");

        Utf8Appendable buffer = impl.getDeclaredConstructor().newInstance();
        // Part 1 is valid
        buffer.append(part1, 0, part1.length);

        assertThrows(Utf8Appendable.NotUtf8Exception.class, () ->
        {
            // Part 2 is invalid
            buffer.append(part2, 0, part2.length);
        });
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testPartialUnsplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String seq2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";

        utf8.append(BufferUtil.toBuffer(seq1, StandardCharsets.UTF_8));
        String ret1 = utf8.takePartialString();

        utf8.append(BufferUtil.toBuffer(seq2, StandardCharsets.UTF_8));
        String ret2 = utf8.takePartialString();

        assertThat("Seq1", ret1, is(seq1));
        assertThat("Seq2", ret2, is(seq2));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testPartialSplitCodepoint(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(TypeUtil.fromHexString(seq1));
        String ret1 = utf8.takePartialString();

        utf8.append(TypeUtil.fromHexString(seq2));
        String ret2 = utf8.takePartialString();

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testPartialSplitCodepointWithNoBuf(Class<Utf8Appendable> impl) throws Exception
    {
        Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(TypeUtil.fromHexString(seq1));
        String ret1 = utf8.takePartialString();

        String ret2 = utf8.takePartialString();

        utf8.append(TypeUtil.fromHexString(seq2));
        String ret3 = utf8.takePartialString();

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is(""));
        assertThat("Seq3", ret3, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
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

        for (Class<? extends Utf8Appendable> impl : APPENDABLE_IMPLS)
        {
            for (String hex : samples)
            {
                tests.add(dynamicTest(impl.getSimpleName() + " : " + hex, () ->
                {
                    Utf8Appendable utf8 = impl.getDeclaredConstructor().newInstance();
                    assertThrows(NotUtf8Exception.class, () -> utf8.append(TypeUtil.fromHexString(hex)));
                }));
            }
        }

        return tests.iterator();
    }
}
