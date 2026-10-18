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
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class HeadersGenerateParseTest
{
    @Test
    public void testGenerateParse()
    {
        HttpURI uri = HttpURI.from("http://host:1234/path?a=b");
        HttpFields fields = HttpFields.build()
            .put("User-Agent", "Jetty")
            .put("Cookie", "c=d");
        HeadersFrame input = new HeadersFrame(new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, fields), true);

        QpackEncoder encoder = new QpackEncoder(_ -> {});
        encoder.setMaxHeadersSize(4 * 1024);
        ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
        BufferAccumulator accumulator = new BufferAccumulator();
        new MessageGenerator(bufferPool, encoder, true).generate(accumulator, 0, input, null);

        QpackDecoder decoder = new QpackDecoder(_ -> {});
        decoder.setMaxHeadersSize(4 * 1024);
        decoder.setBeginNanoTimeSupplier(NanoTime::now);
        List<HeadersFrame> frames = new ArrayList<>();
        MessageParser parser = new MessageParser(new ParserListener()
        {
            @Override
            public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
            {
                frames.add(frame);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        try (RetainableByteBuffer buffer = accumulator.take())
        {
            parser.parse(buffer, false);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(1, frames.size());
        HeadersFrame output = frames.getFirst();

        MetaData.Request inputMetaData = (MetaData.Request)input.getMetaData();
        MetaData.Request outputMetaData = (MetaData.Request)output.getMetaData();
        assertEquals(inputMetaData.getMethod(), outputMetaData.getMethod());
        assertEquals(inputMetaData.getHttpURI().toString(), outputMetaData.getHttpURI().toString());
        assertEquals(inputMetaData.getHttpFields(), outputMetaData.getHttpFields());
    }

    @Test
    public void testParseSplitFrame()
    {
        HeadersFrame input = newHeadersFrame();
        byte[] bytes = generate(input);

        // Split the frame at every position, including inside the frame header.
        for (int split = 1; split < bytes.length; ++split)
        {
            ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
            List<HeadersFrame> frames = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            MessageParser parser = newMessageParser(frames, failures);

            assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, Arrays.copyOfRange(bytes, 0, split)), "split at " + split);
            assertEquals(MessageParser.Result.FRAME, parse(parser, bufferPool, Arrays.copyOfRange(bytes, split, bytes.length)), "split at " + split);

            assertEquals(List.of(), failures, "split at " + split);
            assertEquals(1, frames.size(), "split at " + split);
            MetaData.Request inputMetaData = (MetaData.Request)input.getMetaData();
            MetaData.Request outputMetaData = (MetaData.Request)frames.getFirst().getMetaData();
            assertEquals(inputMetaData.getHttpURI().toString(), outputMetaData.getHttpURI().toString(), "split at " + split);
            assertEquals(inputMetaData.getHttpFields(), outputMetaData.getHttpFields(), "split at " + split);
            assertEquals(0, bufferPool.getLeaks().size(), "split at " + split);
        }
    }

    @Test
    public void testPartialFrameDoesNotRetainNetworkBuffer()
    {
        byte[] bytes = generate(newHeadersFrame());
        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        List<HeadersFrame> frames = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        MessageParser parser = newMessageParser(frames, failures);

        // The frame never completes, for example because the stream is reset,
        // so nothing would ever release a network buffer retained by the parser.
        assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, Arrays.copyOfRange(bytes, 0, bytes.length / 2)));

        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
        assertEquals(List.of(), frames);
        assertEquals(List.of(), failures);
    }

    @Test
    public void testDeclaredLengthTooLarge()
    {
        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        List<HeadersFrame> frames = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        MessageParser parser = newMessageParser(frames, failures);

        // HEADERS frame type, followed by a 1 GiB frame length, followed by few body bytes.
        byte[] bytes = {0x01, (byte)0xC0, 0, 0, 0, 0x40, 0, 0, 0, 1, 2, 3};
        assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, bytes));
        assertEquals(List.of("headers_too_large"), failures);

        // More bytes may arrive before the session is closed: they must be
        // rejected as well, without allocating the declared frame length.
        assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, new byte[]{4, 5, 6}));
        assertEquals(List.of("headers_too_large", "headers_too_large"), failures);

        assertEquals(List.of(), frames);
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    @Test
    public void testDeclaredLengthTooLargeWithoutMaxHeadersSize()
    {
        // HEADERS frame type, followed by frame lengths that do not
        // fit an int (2 GiB and 4 GiB + 16), followed by few body bytes.
        byte[][] frames = {
            {0x01, (byte)0xC0, 0, 0, 0, (byte)0x80, 0, 0, 0, 1, 2, 3},
            {0x01, (byte)0xC0, 0, 0, 1, 0, 0, 0, 16, 1, 2, 3}
        };
        for (byte[] bytes : frames)
        {
            ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
            List<HeadersFrame> headers = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            MessageParser parser = newMessageParser(0, headers, failures);

            assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, bytes));

            assertEquals(List.of("headers_too_large"), failures);
            assertEquals(List.of(), headers);
            assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
        }
    }

    @Test
    public void testPartialFrameDoesNotAllocateDeclaredLength()
    {
        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        List<HeadersFrame> frames = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        MessageParser parser = newMessageParser(0, frames, failures);

        // HEADERS frame type, followed by the max allowed frame length, followed by few body bytes.
        // No array of this length can be allocated, so the parser would fail if it tried to.
        byte[] bytes = {0x01, (byte)0xC0, 0, 0, 0, 0x7F, (byte)0xFF, (byte)0xFF, (byte)0xFF, 1, 2, 3};
        assertEquals(MessageParser.Result.NO_FRAME, parse(parser, bufferPool, bytes));

        assertEquals(List.of(), failures);
        assertEquals(List.of(), frames);
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    @Test
    public void testParseLargeFrameInManyChunks()
    {
        // The frame is larger than the initial capacity used to aggregate
        // it, and it is split in chunks so that the aggregation must grow.
        int maxHeadersSize = 128 * 1024;
        String value = "x".repeat(64 * 1024);
        HttpFields fields = HttpFields.build().put("X-Large", value);
        HeadersFrame input = new HeadersFrame(new MetaData.Request(HttpMethod.GET.asString(), HttpURI.from("http://host:1234/path"), HttpVersion.HTTP_3, fields), true);
        byte[] bytes = generate(maxHeadersSize, input);

        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        List<HeadersFrame> frames = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        MessageParser parser = newMessageParser(maxHeadersSize, frames, failures);

        int chunkSize = 1024;
        MessageParser.Result result = MessageParser.Result.NO_FRAME;
        for (int offset = 0; offset < bytes.length; offset += chunkSize)
        {
            assertEquals(MessageParser.Result.NO_FRAME, result, "offset " + offset);
            result = parse(parser, bufferPool, Arrays.copyOfRange(bytes, offset, Math.min(offset + chunkSize, bytes.length)));
        }

        assertEquals(MessageParser.Result.FRAME, result);
        assertEquals(List.of(), failures);
        assertEquals(1, frames.size());
        assertEquals(value, frames.getFirst().getMetaData().getHttpFields().get("X-Large"));
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    private static HeadersFrame newHeadersFrame()
    {
        HttpURI uri = HttpURI.from("http://host:1234/path?a=b");
        HttpFields fields = HttpFields.build()
            .put("User-Agent", "Jetty")
            .put("Cookie", "c=d");
        return new HeadersFrame(new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, fields), true);
    }

    private static byte[] generate(HeadersFrame frame)
    {
        return generate(4 * 1024, frame);
    }

    private static byte[] generate(int maxHeadersSize, HeadersFrame frame)
    {
        QpackEncoder encoder = new QpackEncoder(_ -> {});
        encoder.setMaxHeadersSize(maxHeadersSize);
        BufferAccumulator accumulator = new BufferAccumulator();
        new MessageGenerator(ByteBufferPool.NON_POOLING, encoder, true).generate(accumulator, 0, frame, null);
        try (RetainableByteBuffer buffer = accumulator.take())
        {
            byte[] bytes = new byte[(int)buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
    }

    private static MessageParser newMessageParser(List<HeadersFrame> frames, List<String> failures)
    {
        return newMessageParser(4 * 1024, frames, failures);
    }

    private static MessageParser newMessageParser(int maxHeadersSize, List<HeadersFrame> frames, List<String> failures)
    {
        QpackDecoder decoder = new QpackDecoder(_ -> {});
        decoder.setMaxHeadersSize(maxHeadersSize);
        MessageParser parser = new MessageParser(new ParserListener()
        {
            @Override
            public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
            {
                frames.add(frame);
            }

            @Override
            public void onSessionFailure(long error, String reason, Throwable failure)
            {
                failures.add(reason);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        return parser;
    }

    /**
     * Parses the given bytes from a pooled network buffer, and releases it like
     * HTTP3StreamConnection does, asserting that the bytes have been consumed.
     */
    private static MessageParser.Result parse(MessageParser parser, ByteBufferPool bufferPool, byte[] bytes)
    {
        try (RetainableByteBuffer.Mutable buffer = bufferPool.acquire(bytes.length, false))
        {
            buffer.put(bytes);
            MessageParser.Result result = parser.parse(buffer, false);
            assertFalse(buffer.hasRemaining());
            return result;
        }
    }
}
