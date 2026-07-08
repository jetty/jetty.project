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

package org.eclipse.jetty.quic.common.frames;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.util.QuicException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FrameGeneratorParserTest
{
    private final ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
    private final FramesGenerator generator = new FramesGenerator(byteBufferPool);
    private final FramesParser parser = new FramesParser();

    private <T extends Frame> List<T> generateParse(T frame)
    {
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        generator.generateFrame(accumulator, frame, Integer.MAX_VALUE);
        return parse(accumulator);
    }

    @SuppressWarnings("unchecked")
    private <T extends Frame> List<T> parse(RetainableByteBuffer.Mutable accumulator)
    {
        T frame1 = (T)parser.parse(accumulator);

        accumulator.getByteBuffer().flip();

        while (accumulator.hasRemaining())
        {
            T frame2 = (T)parser.parse(accumulator);
            if (frame2 != null)
                return List.of(frame1, frame2);
        }

        throw new AssertionError();
    }

    @Test
    public void testAckFrame()
    {
        AckFrame frame = new AckFrame(110, 0, 10, List.of(new AckFrame.AckRange(6, 2), new AckFrame.AckRange(13, 5)));
        List<AckFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(AckFrame::type, frame, result);
            assertEqual(AckFrame::largestAcknowledged, frame, result);
            assertEqual(AckFrame::firstRangeLength, frame, result);
            assertEquals(frame.ackRanges(), result.ackRanges());
        });
    }

    @Test
    public void testAckFrameWithInvalidFirstRangeLength()
    {
        int largest = 110;
        AckFrame frame = new AckFrame(largest, 0, largest * 2, List.of());
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        generator.generateFrame(accumulator, frame, Integer.MAX_VALUE);
        assertThrows(QuicException.class, () -> parser.parse(accumulator));
    }

    @Test
    public void testAckFrameWithInvalidRangeGap()
    {
        int largest = 110;
        AckFrame frame = new AckFrame(largest, 0, 10, List.of(new AckFrame.AckRange(largest * 2, 1)));
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        generator.generateFrame(accumulator, frame, Integer.MAX_VALUE);
        assertThrows(QuicException.class, () -> parser.parse(accumulator));
    }

    @Test
    public void testAckFrameWithInvalidRangeLength()
    {
        int largest = 110;
        AckFrame frame = new AckFrame(largest, 0, 10, List.of(new AckFrame.AckRange(1, largest * 2)));
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        generator.generateFrame(accumulator, frame, Integer.MAX_VALUE);
        assertThrows(QuicException.class, () -> parser.parse(accumulator));
    }

    @Test
    public void testAckFrameRanges()
    {
        AckFrame frame = new AckFrame(
            110, 0, 10, List.of(
                new AckFrame.AckRange(6, 2),
                new AckFrame.AckRange(13, 5)
            )
        );
        List<Long> expected = List.of(
            110L, 109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L, 100L,
            92L, 91L, 90L,
            75L, 74L, 73L, 72L, 71L, 70L
        );
        assertEquals(expected, frame.allAcknowledged());
    }

    @Test
    public void testAckFrameWithWeirdRanges()
    {
        AckFrame frame = new AckFrame(
            110, 0, 2, List.of(
                new AckFrame.AckRange(0, 3),
                new AckFrame.AckRange(1, 0),
                new AckFrame.AckRange(0, 0),
                new AckFrame.AckRange(1, 1)
            )
        );
        List<Long> expected = List.of(
            110L, 109L, 108L,
            106L, 105L, 104L, 103L,
            100L,
            98L,
            95L, 94L
        );
        assertEquals(expected, frame.allAcknowledged());
    }

    @Test
    public void testResetFrame()
    {
        long finalSize = 218921347;
        ResetStreamFrame frame = new ResetStreamFrame(Integer.MAX_VALUE + 23L, 1, -1);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        GeneratedFrame generatedFrame = generator.generateResetStreamFrame(accumulator, frame, finalSize, Integer.MAX_VALUE);
        assertNotNull(generatedFrame);
        try (ResetStreamFrame generated = (ResetStreamFrame)generatedFrame.frame())
        {
            List<ResetStreamFrame> list = parse(accumulator);
            list.forEach(result ->
            {
                assertEqual(ResetStreamFrame::type, generated, result);
                assertEqual(ResetStreamFrame::streamId, generated, result);
                assertEqual(ResetStreamFrame::applicationErrorCode, generated, result);
                assertEqual(ResetStreamFrame::finalSize, generated, result);
            });
        }
    }

    @Test
    public void testStopSendingFrame()
    {
        StopSendingFrame frame = new StopSendingFrame(Integer.MAX_VALUE + 37L, 77);
        List<StopSendingFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StopSendingFrame::type, frame, result);
            assertEqual(StopSendingFrame::streamId, frame, result);
            assertEqual(StopSendingFrame::applicationErrorCode, frame, result);
        });
    }

    @Test
    public void testStreamFrame()
    {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode("DATA");
        StreamFrame frame = new StreamFrame(3290901290300L, RetainableByteBuffer.wrap(bytes), -1, true, true, true);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        GeneratedFrame generatedFrame = generator.generateStreamFrame(accumulator, frame, 120911129347656L, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertNotNull(generatedFrame);
        try (StreamFrame generated = (StreamFrame)generatedFrame.frame())
        {
            bytes.clear();
            List<StreamFrame> list = parse(accumulator);
            generated.rewind();
            list.forEach(result -> assertStreamFrameEqual(generated, result));
        }
    }

    @Test
    public void testStreamFrameLengthZeroMaxDataZero()
    {
        StreamFrame frame = new StreamFrame(3290901290300L, RetainableByteBuffer.EMPTY, -1, true, true, true);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        // Use zero maxData, the frame must be generated.
        GeneratedFrame generatedFrame = generator.generateStreamFrame(accumulator, frame, 120911129347656L, 0, Integer.MAX_VALUE);
        assertNotNull(generatedFrame);
        try (StreamFrame generated = (StreamFrame)generatedFrame.frame())
        {
            List<StreamFrame> list = parse(accumulator);
            list.forEach(result -> assertStreamFrameEqual(generated, result));
        }
    }

    @Test
    public void testStreamFrameMaxBytes67()
    {
        // StreamFrame format: type(i) + streamId(i) + offset(i) + length(i) + data.
        // A congestion window of 67-68 bytes with 1 byte for type, streamId, offset
        // and length is special, because the frame length field goes from being
        // VarLenInt encoded with 1 byte (0-63) to 2 bytes (64-16383).
        // So despite 67-68 bytes are available, the generation only produces 66-67.
        ByteBuffer bytes = ByteBuffer.allocate(64);
        StreamFrame frame = new StreamFrame(0L, RetainableByteBuffer.wrap(bytes), -1, true, true, true);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int maxBytes = 67;
        GeneratedFrame generatedFrame = generator.generateStreamFrame(accumulator, frame, 1L, Integer.MAX_VALUE, maxBytes);
        assertNotNull(generatedFrame);
        assertEquals(maxBytes - 1, generatedFrame.length());
        assertEquals(2, frame.remaining());
        try (StreamFrame generated = (StreamFrame)generatedFrame.frame())
        {
            bytes.clear();
            List<StreamFrame> list = parse(accumulator);
            generated.rewind();
            list.forEach(result -> assertStreamFrameEqual(generated, result));
        }
    }

    public static void assertStreamFrameEqual(StreamFrame frame, StreamFrame result)
    {
        assertEqual(StreamFrame::type, frame, result);
        assertEqual(StreamFrame::streamId, frame, result);
        assertEqual(StreamFrame::offset, frame, result);
        assertEquals(frame.map(RetainableByteBuffer::getByteBuffer), result.map(RetainableByteBuffer::getByteBuffer));
        assertEqual(StreamFrame::isEndStream, frame, result);
    }

    @Test
    public void testMaxDataFrame()
    {
        MaxDataFrame frame = new MaxDataFrame(34578932932L);
        List<MaxDataFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(MaxDataFrame::type, frame, result);
            assertEqual(MaxDataFrame::maxData, frame, result);
        });
    }

    @Test
    public void testStreamMaxDataFrame()
    {
        StreamMaxDataFrame frame = new StreamMaxDataFrame(34578932932L, 6745376092L);
        List<StreamMaxDataFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamMaxDataFrame::type, frame, result);
            assertEqual(StreamMaxDataFrame::maxData, frame, result);
        });
    }

    @Test
    public void testMaxStreamsFrame()
    {
        MaxStreamsFrame frame = new MaxStreamsFrame(3209832, true);
        List<MaxStreamsFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(MaxStreamsFrame::type, frame, result);
            assertEqual(MaxStreamsFrame::maxStreams, frame, result);
        });
    }

    @Test
    public void testDataBlockedFrame()
    {
        DataBlockedFrame frame = new DataBlockedFrame(23890001);
        List<DataBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(DataBlockedFrame::type, frame, result);
            assertEqual(DataBlockedFrame::offset, frame, result);
        });
    }

    @Test
    public void testStreamDataBlockedFrame()
    {
        StreamDataBlockedFrame frame = new StreamDataBlockedFrame(239039854678345L, 53476834501L);
        List<StreamDataBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamDataBlockedFrame::type, frame, result);
            assertEqual(StreamDataBlockedFrame::streamId, frame, result);
            assertEqual(StreamDataBlockedFrame::offset, frame, result);
        });
    }

    @Test
    public void testStreamsBlockedFrame()
    {
        StreamsBlockedFrame frame = new StreamsBlockedFrame(true, 92921018934L);
        List<StreamsBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamsBlockedFrame::type, frame, result);
            assertEqual(StreamsBlockedFrame::maxStreams, frame, result);
        });
    }

    @Test
    public void testConnectionCloseFrame()
    {
        ConnectionCloseFrame frame = new ConnectionCloseFrame(13, "ERROR", 11);
        List<ConnectionCloseFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(ConnectionCloseFrame::type, frame, result);
            assertEqual(ConnectionCloseFrame::errorCode, frame, result);
            assertEqual(ConnectionCloseFrame::reason, frame, result);
            assertEqual(ConnectionCloseFrame::causeFrameType, frame, result);
        });
    }

    private static <F extends Frame, R> void assertEqual(Function<F, R> getter, F frame1, F frame2)
    {
        R result1 = getter.apply(frame1);
        R result2 = getter.apply(frame2);
        if (result1 != null && result1.getClass().isArray())
            assertArrayEquals((byte[])result1, (byte[])result2);
        else
            assertEquals(result1, result2);
    }
}
