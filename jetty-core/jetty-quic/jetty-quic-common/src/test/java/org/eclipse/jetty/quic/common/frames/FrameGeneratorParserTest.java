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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    public void testResetFrame()
    {
        ResetFrame frame = new ResetFrame(Integer.MAX_VALUE + 23L, 1, 218921347);
        List<ResetFrame> list = generateParse(frame);
        list.forEach(result ->
        {
            assertEqual(ResetFrame::type, frame, result);
            assertEqual(ResetFrame::streamId, frame, result);
            assertEqual(ResetFrame::applicationErrorCode, frame, result);
            assertEqual(ResetFrame::finalSize, frame, result);
        });
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
    public void testStreamFrame() throws Exception
    {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode("DATA");
        StreamFrame frame = new StreamFrame(3290901290300L, RetainableByteBuffer.wrap(bytes), 120911129347656L, true, true, true);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        generator.generateFrame(accumulator, frame, Integer.MAX_VALUE);
        bytes.clear();
        List<StreamFrame> list = parse(accumulator);
        list.forEach(result -> assertStreamFrameEqual(frame, result));
    }

    public static void assertStreamFrameEqual(StreamFrame frame, StreamFrame result)
    {
        assertEqual(StreamFrame::type, frame, result);
        assertEqual(StreamFrame::streamId, frame, result);
        assertEqual(StreamFrame::offset, frame, result);
        assertEquals(frame.data().getByteBuffer(), result.data().getByteBuffer());
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
