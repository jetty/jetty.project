//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.parser.DecoderInstructionParser;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DecoderInstructionParserTest
{
    public static class DebugHandler implements DecoderInstructionParser.Handler
    {
        public Queue<Integer> sectionAcknowledgements = new LinkedList<>();
        public Queue<Integer> streamCancellations = new LinkedList<>();
        public Queue<Integer> insertCountIncrements = new LinkedList<>();

        @Override
        public void onSectionAcknowledgement(int streamId)
        {
            sectionAcknowledgements.add(streamId);
        }

        @Override
        public void onStreamCancellation(int streamId)
        {
            streamCancellations.add(streamId);
        }

        @Override
        public void onInsertCountIncrement(int increment)
        {
            insertCountIncrements.add(increment);
        }

        public boolean isEmpty()
        {
            return sectionAcknowledgements.isEmpty() && streamCancellations.isEmpty() && insertCountIncrements.isEmpty();
        }
    }

    @Test
    public void testSectionAcknowledgement() throws Exception
    {
        DebugHandler debugHandler = new DebugHandler();
        DecoderInstructionParser incomingEncoderStream = new DecoderInstructionParser(debugHandler);

        // Example from the spec, section acknowledgement instruction with stream id 4.
        String encoded = "84";
        ByteBuffer buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        incomingEncoderStream.parse(buffer);
        assertThat(debugHandler.sectionAcknowledgements.poll(), is(4));
        assertTrue(debugHandler.isEmpty());

        // 1111 1110 == FE is largest value we can do without continuation should be stream ID 126.
        encoded = "FE";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        incomingEncoderStream.parse(buffer);
        assertThat(debugHandler.sectionAcknowledgements.poll(), is(126));
        assertTrue(debugHandler.isEmpty());

        // 1111 1111 0000 0000 == FF00 is next value, stream id 127.
        encoded = "FF00";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        incomingEncoderStream.parse(buffer);
        assertThat(debugHandler.sectionAcknowledgements.poll(), is(127));
        assertTrue(debugHandler.isEmpty());

        // 1111 1111 0000 0001 == FF01 is next value, stream id 128.
        encoded = "FF01";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        incomingEncoderStream.parse(buffer);
        assertThat(debugHandler.sectionAcknowledgements.poll(), is(128));
        assertTrue(debugHandler.isEmpty());

        // FFBA09 contains section ack with stream ID of 1337, this contains an octet with continuation bit.
        encoded = "FFBA09";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(encoded));
        incomingEncoderStream.parse(buffer);
        assertThat(debugHandler.sectionAcknowledgements.poll(), is(1337));
        assertTrue(debugHandler.isEmpty());

        // Test with continuation.
        encoded = "FFBA09";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        for (int i = 0; i < 10; i++)
        {
            ByteBuffer buffer1 = BufferUtil.toBuffer(bytes, 0, 2);
            ByteBuffer buffer2 = BufferUtil.toBuffer(bytes, 2, 1);
            incomingEncoderStream.parse(buffer1);
            assertTrue(debugHandler.isEmpty());
            incomingEncoderStream.parse(buffer2);
            assertThat(debugHandler.sectionAcknowledgements.poll(), is(1337));
            assertTrue(debugHandler.isEmpty());
        }
    }

    @Disabled
    @Test
    public void testStreamCancellation() throws Exception
    {
        // TODO: Write this test.
        throw new RuntimeException("TODO: testStreamCancellation");
    }

    @Disabled
    @Test
    public void testInsertCountIncrement() throws Exception
    {
        // TODO: Write this test.
        throw new RuntimeException("TODO: testInsertCountIncrement");
    }
}
