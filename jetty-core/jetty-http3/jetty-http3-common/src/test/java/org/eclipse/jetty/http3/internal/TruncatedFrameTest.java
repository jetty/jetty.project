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

package org.eclipse.jetty.http3.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Tests that a stream that terminates cleanly in the middle of
 * a frame is an {@code H3_FRAME_ERROR}, as per RFC 9114, section 7.1.</p>
 */
public class TruncatedFrameTest
{
    private static final long FRAME_ERROR = HTTP3ErrorCode.FRAME_ERROR.code();

    private final List<HeadersFrame> headers = new ArrayList<>();
    private final List<Boolean> data = new ArrayList<>();
    private final List<Long> errors = new ArrayList<>();
    private final MessageParser parser = newMessageParser();

    private MessageParser newMessageParser()
    {
        QpackDecoder decoder = new QpackDecoder(_ -> {});
        decoder.setMaxHeadersSize(4 * 1024);
        MessageParser parser = new MessageParser(new ParserListener()
        {
            @Override
            public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
            {
                headers.add(frame);
            }

            @Override
            public void onData(long streamId, DataFrame frame)
            {
                data.add(frame.isLast());
            }

            @Override
            public void onSessionFailure(long error, String reason, Throwable failure)
            {
                errors.add(error);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        return parser;
    }

    private MessageParser.Result parse(boolean last, int... bytes)
    {
        byte[] array = new byte[bytes.length];
        for (int i = 0; i < bytes.length; ++i)
        {
            array[i] = (byte)bytes[i];
        }
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(array);
        MessageParser.Result result = parser.parse(buffer, last);
        assertFalse(buffer.hasRemaining());
        return result;
    }

    private static byte[] generateHeaders()
    {
        HttpURI uri = HttpURI.from("http://host:1234/path");
        MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        QpackEncoder encoder = new QpackEncoder(_ -> {});
        encoder.setMaxHeadersSize(4 * 1024);
        BufferAccumulator accumulator = new BufferAccumulator();
        new MessageGenerator(ByteBufferPool.NON_POOLING, encoder, true).generate(accumulator, 0, new HeadersFrame(request, true), null);
        try (RetainableByteBuffer buffer = accumulator.take())
        {
            byte[] bytes = new byte[(int)buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
    }

    public static List<int[]> truncatedFrames()
    {
        return List.of(
            // Inside the frame type, encoded with 8 bytes.
            new int[]{0xC0, 0x00},
            // Before the frame length.
            new int[]{0x01},
            // Inside the frame length, encoded with 8 bytes.
            new int[]{0x01, 0xC0},
            // Before the HEADERS body.
            new int[]{0x01, 0x05},
            // Inside the HEADERS body.
            new int[]{0x01, 0x05, 0x01, 0x02},
            // Before the DATA body.
            new int[]{0x00, 0x05},
            // Before the body of an unknown frame.
            new int[]{0x21, 0x05},
            // Inside the body of an unknown frame.
            new int[]{0x21, 0x05, 0x01, 0x02}
        );
    }

    @ParameterizedTest
    @MethodSource("truncatedFrames")
    public void testLastBytesAreTruncated(int[] bytes)
    {
        assertEquals(MessageParser.Result.NO_FRAME, parse(true, bytes));
        assertEquals(List.of(FRAME_ERROR), errors);
    }

    @ParameterizedTest
    @MethodSource("truncatedFrames")
    public void testBytesFollowedByEmptyLast(int[] bytes)
    {
        assertEquals(MessageParser.Result.NO_FRAME, parse(false, bytes));
        assertEquals(List.of(), errors);

        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(FRAME_ERROR), errors);
    }

    @Test
    public void testDataBodyTruncated()
    {
        // The available DATA bytes are delivered as a fragment.
        assertEquals(MessageParser.Result.FRAME, parse(true, 0x00, 0x05, 0x01, 0x02));
        assertEquals(List.of(false), data);
        assertEquals(List.of(), errors);

        // The same chunk with the FIN is parsed again.
        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(false), data);
        assertEquals(List.of(FRAME_ERROR), errors);
    }

    @Test
    public void testEmptyStreamIsNotTruncated()
    {
        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(), errors);
    }

    @Test
    public void testCompleteDataFrameIsNotTruncated()
    {
        assertEquals(MessageParser.Result.FRAME, parse(true, 0x00, 0x02, 0x01, 0x02));
        assertEquals(List.of(true), data);

        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(), errors);
    }

    @Test
    public void testEmptyDataFrameIsNotTruncated()
    {
        assertEquals(MessageParser.Result.FRAME, parse(true, 0x00, 0x00));
        assertEquals(List.of(true), data);

        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(), errors);
    }

    @Test
    public void testCompleteUnknownFrameIsNotTruncated()
    {
        assertEquals(MessageParser.Result.NO_FRAME, parse(true, 0x21, 0x02, 0x01, 0x02));
        assertEquals(List.of(), errors);
    }

    @Test
    public void testCompleteHeadersFrameIsNotTruncated()
    {
        byte[] bytes = generateHeaders();
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(bytes);
        assertEquals(MessageParser.Result.FRAME, parser.parse(buffer, true));
        assertEquals(1, headers.size());
        assertTrue(headers.getFirst().isLast());

        assertEquals(MessageParser.Result.NO_FRAME, parse(true));
        assertEquals(List.of(), errors);
    }

    @Test
    public void testCompleteHeadersFrameSplitIsNotTruncated()
    {
        byte[] bytes = generateHeaders();
        int split = bytes.length / 2;
        assertEquals(MessageParser.Result.NO_FRAME, parser.parse(RetainableByteBuffer.wrap(bytes, 0, split), false));
        assertEquals(MessageParser.Result.FRAME, parser.parse(RetainableByteBuffer.wrap(bytes, split, bytes.length - split), true));
        assertEquals(1, headers.size());
        assertEquals(List.of(), errors);
    }

    @Test
    public void testHeadersDeclaredTooLargeFailsOnlyOnce()
    {
        // HEADERS frame type, followed by a 1 GiB frame length, followed by few body bytes.
        assertEquals(MessageParser.Result.NO_FRAME, parse(true, 0x01, 0xC0, 0, 0, 0, 0x40, 0, 0, 0, 1, 2, 3));
        assertEquals(1, errors.size());
        assertFalse(errors.contains(FRAME_ERROR));
    }

    @Test
    public void testInvalidHeadersFailsOnlyOnce()
    {
        // A complete HEADERS frame whose field section cannot be decoded.
        assertEquals(MessageParser.Result.NO_FRAME, parse(true, 0x01, 0x02, 0xFF, 0xFF));
        assertEquals(1, errors.size());
        assertFalse(errors.contains(FRAME_ERROR));
    }

    @Test
    public void testControlFrameOnMessageStreamFailsOnlyOnce()
    {
        // A SETTINGS frame, that is invalid on a message stream.
        assertEquals(MessageParser.Result.NO_FRAME, parse(true, 0x04, 0x01, 0x00));
        assertEquals(List.of(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code()), errors);
    }
}
