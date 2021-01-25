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

package org.eclipse.jetty.websocket.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test partial UTF8 String sequence building.
 */
public class Utf8PartialBuilderTest
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private ByteBuffer toByteBuffer(String hexStr)
    {
        return ByteBuffer.wrap(Hex.asByteArray(hexStr));
    }

    @Test
    public void testPartialUnsplitCodepoint()
    {
        Utf8PartialBuilder utf8 = new Utf8PartialBuilder();

        String seq1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String seq2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";

        String ret1 = utf8.toPartialString(BufferUtil.toBuffer(seq1, StandardCharsets.UTF_8));
        String ret2 = utf8.toPartialString(BufferUtil.toBuffer(seq2, StandardCharsets.UTF_8));

        assertThat("Seq1", ret1, is(seq1));
        assertThat("Seq2", ret2, is(seq2));
    }

    @Test
    public void testPartialSplitCodepoint()
    {
        Utf8PartialBuilder utf8 = new Utf8PartialBuilder();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        String ret1 = utf8.toPartialString(toByteBuffer(seq1));
        String ret2 = utf8.toPartialString(toByteBuffer(seq2));

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }

    @Test
    public void testPartialSplitCodepointWithNoBuf()
    {
        Utf8PartialBuilder utf8 = new Utf8PartialBuilder();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        String ret1 = utf8.toPartialString(toByteBuffer(seq1));
        String ret2 = utf8.toPartialString(BufferUtil.EMPTY_BUFFER);
        String ret3 = utf8.toPartialString(toByteBuffer(seq2));

        assertThat("Seq1", ret1, is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2", ret2, is(""));
        assertThat("Seq3", ret3, is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }
}
