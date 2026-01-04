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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FrameGeneratorParserTest
{
    private final ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
    private final FrameGenerator generator = new FrameGenerator(byteBufferPool);
    private final FramesParser parser = new FramesParser();

    @BeforeEach
    public void prepare()
    {
        parser.setFrameMaxSize(Frame.DEFAULT_MAX_SIZE);
    }

    private <T extends Frame> List<T> generateParse(T frame)
    {
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        generator.generate(accumulator, frame);
        return parse(accumulator);
    }

    @SuppressWarnings("unchecked")
    private <T extends Frame> List<T> parse(ByteBufferPool.Accumulator accumulator)
    {
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        int capacity = buffers.stream().mapToInt(Buffer::remaining).sum();
        ByteBuffer byteBuffer = ByteBuffer.allocate(capacity);
        buffers.stream().map(ByteBuffer::slice).forEach(byteBuffer::put);
        byteBuffer.flip();
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(byteBuffer);

        T frame1 = (T)parser.parse(buffer);

        byteBuffer.flip();

        while (buffer.hasRemaining())
        {
            T frame2 = (T)parser.parse(buffer);
            if (frame2 != null)
                return List.of(frame1, frame2);
        }

        throw new AssertionError();
    }

    @Test
    public void testResetFrame()
    {
        ResetFrame frame = new ResetFrame(Integer.MAX_VALUE + 23L, 1, 218921347);
        List<ResetFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(ResetFrame::getFrameType, frame, result);
            assertEqual(ResetFrame::getStreamId, frame, result);
            assertEqual(ResetFrame::getApplicationErrorCode, frame, result);
            assertEqual(ResetFrame::getFinalSize, frame, result);
        });
    }

    @Test
    public void testStopSendingFrame()
    {
        StopSendingFrame frame = new StopSendingFrame(Integer.MAX_VALUE + 37L, 77);
        List<StopSendingFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StopSendingFrame::getFrameType, frame, result);
            assertEqual(StopSendingFrame::getStreamId, frame, result);
            assertEqual(StopSendingFrame::getApplicationErrorCode, frame, result);
        });
    }

    @Test
    public void testStreamFrame()
    {
        byte[] bytes = "DATA".getBytes();
        StreamFrame frame = new StreamFrame(3290901290300L, ByteBuffer.wrap(bytes), 120911129347656L, true, true);
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        generator.generate(accumulator, frame, bytes.length, Frame.DEFAULT_MAX_SIZE);
        List<StreamFrame> list = parse(accumulator);
        list.forEach(result -> assertStreamFrameEqual(frame, result));
    }

    public static void assertStreamFrameEqual(StreamFrame frame, StreamFrame result)
    {
        assertEqual(StreamFrame::getFrameType, frame, result);
        assertEqual(StreamFrame::getStreamId, frame, result);
        assertEqual(StreamFrame::getOffset, frame, result);
        assertEqual(StreamFrame::getData, frame, result);
        assertEqual(StreamFrame::isEndStream, frame, result);
    }

    @Test
    public void testMaxDataFrame()
    {
        MaxDataFrame frame = new MaxDataFrame(34578932932L);
        List<MaxDataFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(MaxDataFrame::getFrameType, frame, result);
            assertEqual(MaxDataFrame::getMaxData, frame, result);
        });
    }

    @Test
    public void testStreamMaxDataFrame()
    {
        StreamMaxDataFrame frame = new StreamMaxDataFrame(34578932932L, 6745376092L);
        List<StreamMaxDataFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamMaxDataFrame::getFrameType, frame, result);
            assertEqual(StreamMaxDataFrame::getMaxData, frame, result);
        });
    }

    @Test
    public void testMaxStreamsFrame()
    {
        MaxStreamsFrame frame = new MaxStreamsFrame(3209832, true);
        List<MaxStreamsFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(MaxStreamsFrame::getFrameType, frame, result);
            assertEqual(MaxStreamsFrame::getMaxStreams, frame, result);
        });
    }

    @Test
    public void testDataBlockedFrame()
    {
        DataBlockedFrame frame = new DataBlockedFrame(23890001);
        List<DataBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(DataBlockedFrame::getFrameType, frame, result);
            assertEqual(DataBlockedFrame::getOffset, frame, result);
        });
    }

    @Test
    public void testStreamDataBlockedFrame()
    {
        StreamDataBlockedFrame frame = new StreamDataBlockedFrame(239039854678345L, 53476834501L);
        List<StreamDataBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamDataBlockedFrame::getFrameType, frame, result);
            assertEqual(StreamDataBlockedFrame::getStreamId, frame, result);
            assertEqual(StreamDataBlockedFrame::getOffset, frame, result);
        });
    }

    @Test
    public void testStreamsBlockedFrame()
    {
        StreamsBlockedFrame frame = new StreamsBlockedFrame(true, 92921018934L);
        List<StreamsBlockedFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(StreamsBlockedFrame::getFrameType, frame, result);
            assertEqual(StreamsBlockedFrame::getMaxStreams, frame, result);
        });
    }

    @Test
    public void testConnectionCloseFrame()
    {
        ConnectionCloseFrame frame = new ConnectionCloseFrame(13, "ERROR", 11);
        List<ConnectionCloseFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(ConnectionCloseFrame::getFrameType, frame, result);
            assertEqual(ConnectionCloseFrame::getErrorCode, frame, result);
            assertEqual(ConnectionCloseFrame::getReason, frame, result);
            assertEqual(ConnectionCloseFrame::getCauseFrameType, frame, result);
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
