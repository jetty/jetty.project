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

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.internal.parser.EncoderInstructionParser;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncoderInstructionParserTest
{
    private EncoderParserDebugHandler _handler;
    private EncoderInstructionParser _instructionParser;

    @BeforeEach
    public void before()
    {
        _handler = new EncoderParserDebugHandler();
        _instructionParser = new EncoderInstructionParser(_handler);
    }

    @Test
    public void testSectionAcknowledgement() throws Exception
    {
        // Example from the spec, section acknowledgement instruction with stream id 4.
        String encoded = "84";
        ByteBuffer buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        _instructionParser.parse(buffer);
        assertThat(_handler.sectionAcknowledgements.poll(), is(4L));
        assertTrue(_handler.isEmpty());

        // 1111 1110 == FE is largest value we can do without continuation should be stream ID 126.
        encoded = "FE";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        _instructionParser.parse(buffer);
        assertThat(_handler.sectionAcknowledgements.poll(), is(126L));
        assertTrue(_handler.isEmpty());

        // 1111 1111 0000 0000 == FF00 is next value, stream id 127.
        encoded = "FF00";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        _instructionParser.parse(buffer);
        assertThat(_handler.sectionAcknowledgements.poll(), is(127L));
        assertTrue(_handler.isEmpty());

        // 1111 1111 0000 0001 == FF01 is next value, stream id 128.
        encoded = "FF01";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        _instructionParser.parse(buffer);
        assertThat(_handler.sectionAcknowledgements.poll(), is(128L));
        assertTrue(_handler.isEmpty());

        // FFBA09 contains section ack with stream ID of 1337, this contains an octet with continuation bit.
        encoded = "FFBA09";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        _instructionParser.parse(buffer);
        assertThat(_handler.sectionAcknowledgements.poll(), is(1337L));
        assertTrue(_handler.isEmpty());

        // Test with continuation.
        encoded = "FFBA09";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        for (int i = 0; i < 10; i++)
        {
            ByteBuffer buffer1 = BufferUtil.toBuffer(bytes, 0, 2);
            ByteBuffer buffer2 = BufferUtil.toBuffer(bytes, 2, 1);
            _instructionParser.parse(buffer1);
            assertTrue(_handler.isEmpty());
            _instructionParser.parse(buffer2);
            assertThat(_handler.sectionAcknowledgements.poll(), is(1337L));
            assertTrue(_handler.isEmpty());
        }
    }

    @Test
    public void testStreamCancellation() throws Exception
    {
        // Stream Cancellation (Stream=8).
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("48");
        _instructionParser.parse(buffer);
        assertThat(_handler.streamCancellations.poll(), is(8L));
        assertTrue(_handler.isEmpty());
    }

    @Test
    public void testInsertCountIncrement() throws Exception
    {
        // Insert Count Increment (1).
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("01");
        _instructionParser.parse(buffer);
        assertThat(_handler.insertCountIncrements.poll(), is(1));
        assertTrue(_handler.isEmpty());
    }
}
