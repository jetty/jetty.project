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

package org.eclipse.jetty.http2.frames;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.HeadersGenerator;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContinuationParseTest
{
    @Test
    public void testParseOneByteAtATime() throws Exception
    {
        ArrayByteBufferPool.Tracking trackingPool = new ArrayByteBufferPool.Tracking();
        WritableBufferPool bufferPool = WritableBufferPool.wrap(trackingPool);
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onHeaders(HeadersFrame frame)
            {
                frames.add(frame);
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                frames.add(new HeadersFrame(null, null, false));
            }
        });

        // Iterate a few times to be sure the parser is properly reset.
        for (int i = 0; i < 2; ++i)
        {
            int streamId = 13;
            HttpFields fields = HttpFields.build()
                .put("Accept", "text/html")
                .put("User-Agent", "Jetty");
            MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.generateHeaders(accumulator, streamId, metaData, null, true);

            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                try (RetainableByteBuffer header = buffer.sliceAndConsume(Frame.HEADER_LENGTH))
                {
                    try (RetainableByteBuffer body = buffer.sliceAndConsume(buffer.remaining()))
                    {
                        long length = body.remaining();
                        long oneThird = length / 3;
                        long lastThird = length - 2 * oneThird;

                        RetainableByteBuffer.Mutable headersHeader = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, header.isDirect());
                        accumulator.addRetained(headersHeader);
                        // Adjust the length of the HEADERS frame to 1/3rd.
                        headersHeader.put((byte)((oneThird >>> 16) & 0xFF));
                        headersHeader.put((byte)((oneThird >>> 8) & 0xFF));
                        headersHeader.put((byte)(oneThird & 0xFF));
                        headersHeader.put(header.get(3));
                        // Remove the END_HEADERS flag from the HEADERS header.
                        headersHeader.put((byte)(header.get(4) & ~Flags.END_HEADERS));
                        headersHeader.put(header.get(5));
                        headersHeader.put(header.get(6));
                        headersHeader.put(header.get(7));
                        headersHeader.put(header.get(8));

                        // New HEADERS body, first 1/3rd.
                        RetainableByteBuffer headersBody1 = body.sliceAndConsume(oneThird);
                        accumulator.addRetained(headersBody1);

                        // Split the rest of the HEADERS body into CONTINUATION frames.
                        // First CONTINUATION header.
                        RetainableByteBuffer.Mutable continuationHeader1 = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, header.isDirect());
                        accumulator.addRetained(continuationHeader1);
                        continuationHeader1.put((byte)((oneThird >>> 16) & 0xFF));
                        continuationHeader1.put((byte)((oneThird >>> 8) & 0xFF));
                        continuationHeader1.put((byte)(oneThird & 0xFF));
                        continuationHeader1.put((byte)FrameType.CONTINUATION.getType());
                        continuationHeader1.put((byte)Flags.NONE);
                        continuationHeader1.put((byte)0x00);
                        continuationHeader1.put((byte)0x00);
                        continuationHeader1.put((byte)0x00);
                        continuationHeader1.put((byte)streamId);

                        // First CONTINUATION body.
                        RetainableByteBuffer continuationBody1 = body.sliceAndConsume(oneThird);
                        accumulator.addRetained(continuationBody1);

                        // Second CONTINUATION header.
                        RetainableByteBuffer.Mutable continuationHeader2 = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, header.isDirect());
                        accumulator.addRetained(continuationHeader2);
                        continuationHeader2.put((byte)((lastThird >>> 16) & 0xFF));
                        continuationHeader2.put((byte)((lastThird >>> 8) & 0xFF));
                        continuationHeader2.put((byte)(lastThird & 0xFF));
                        continuationHeader2.put((byte)FrameType.CONTINUATION.getType());
                        continuationHeader2.put((byte)Flags.END_HEADERS);
                        continuationHeader2.put((byte)0x00);
                        continuationHeader2.put((byte)0x00);
                        continuationHeader2.put((byte)0x00);
                        continuationHeader2.put((byte)streamId);

                        // Second CONTINUATION body.
                        RetainableByteBuffer continuationBody2 = body.sliceAndConsume(lastThird);
                        accumulator.addRetained(continuationBody2);

                        frames.clear();
                        try (RetainableByteBuffer generated = accumulator.drain())
                        {
                            while (generated.hasRemaining())
                            {
                                parser.parse(RetainableByteBuffer.wrap(new byte[]{generated.get()}));
                            }
                        }

                        assertEquals(1, frames.size());
                        HeadersFrame frame = frames.getFirst();
                        assertEquals(streamId, frame.getStreamId());
                        assertTrue(frame.isEndStream());
                        MetaData.Request request = (MetaData.Request)frame.getMetaData();
                        assertEquals(metaData.getMethod(), request.getMethod());
                        assertEquals(metaData.getHttpURI(), request.getHttpURI());
                        for (int j = 0; j < fields.size(); ++j)
                        {
                            HttpField field = fields.getField(j);
                            assertTrue(request.getHttpFields().contains(field));
                        }
                        PriorityFrame priority = frame.getPriority();
                        assertNull(priority);
                    }
                }
            }

            assertEquals(0, trackingPool.getLeaks().size(), trackingPool.dumpLeaks());
        }
    }

    @Test
    public void testBeginNanoTime() throws Exception
    {
        ArrayByteBufferPool.Tracking trackingPool = new ArrayByteBufferPool.Tracking();
        WritableBufferPool bufferPool = WritableBufferPool.wrap(trackingPool);
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        final List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onHeaders(HeadersFrame frame)
            {
                frames.add(frame);
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                frames.add(new HeadersFrame(null, null, false));
            }
        });

        int streamId = 13;
        HttpFields fields = HttpFields.build()
            .put("Accept", "text/html")
            .put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.generateHeaders(accumulator, streamId, metaData, null, true);

        try (RetainableByteBuffer buffer = accumulator.drain())
        {
            try (RetainableByteBuffer header = buffer.sliceAndConsume(Frame.HEADER_LENGTH))
            {
                try (RetainableByteBuffer body = buffer.sliceAndConsume(buffer.remaining()))
                {
                    long length = body.remaining();
                    long firstHalf = length / 2;
                    long lastHalf = length - firstHalf;

                    RetainableByteBuffer.Mutable headersHeader = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, header.isDirect());
                    accumulator.addRetained(headersHeader);
                    // Create the split HEADERS frame.
                    headersHeader.put((byte)((firstHalf >>> 16) & 0xFF));
                    headersHeader.put((byte)((firstHalf >>> 8) & 0xFF));
                    headersHeader.put((byte)(firstHalf & 0xFF));
                    headersHeader.put(header.get(3));
                    // Remove the END_HEADERS flag from the HEADERS header.
                    headersHeader.put((byte)(header.get(4) & ~Flags.END_HEADERS));
                    headersHeader.put(header.get(5));
                    headersHeader.put(header.get(6));
                    headersHeader.put(header.get(7));
                    headersHeader.put(header.get(8));

                    // New HEADERS body.
                    RetainableByteBuffer headersBody1 = body.sliceAndConsume(firstHalf);
                    accumulator.addRetained(headersBody1);

                    try (RetainableByteBuffer generated = accumulator.drain())
                    {
                        parser.parse(generated);
                    }
                    long beginNanoTime = parser.getBeginNanoTime();

                    // Split the rest of the HEADERS body into a CONTINUATION frame.
                    RetainableByteBuffer.Mutable continuationHeader = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, header.isDirect());
                    accumulator.addRetained(continuationHeader);
                    continuationHeader.put((byte)((lastHalf >>> 16) & 0xFF));
                    continuationHeader.put((byte)((lastHalf >>> 8) & 0xFF));
                    continuationHeader.put((byte)(lastHalf & 0xFF));
                    continuationHeader.put((byte)FrameType.CONTINUATION.getType());
                    continuationHeader.put((byte)Flags.END_HEADERS);
                    continuationHeader.put((byte)0x00);
                    continuationHeader.put((byte)0x00);
                    continuationHeader.put((byte)0x00);
                    continuationHeader.put((byte)streamId);

                    // Early parsing.
                    try (RetainableByteBuffer generated = accumulator.drain())
                    {
                        parser.parse(generated);
                    }

                    // CONTINUATION body.
                    RetainableByteBuffer continuationBody = body.sliceAndConsume(lastHalf);
                    accumulator.addRetained(continuationBody);

                    // Finish parsing.
                    try (RetainableByteBuffer generated = accumulator.drain())
                    {
                        parser.parse(generated);
                    }

                    assertEquals(1, frames.size());
                    HeadersFrame frame = frames.getFirst();
                    assertEquals(streamId, frame.getStreamId());
                    assertTrue(frame.isEndStream());
                    MetaData.Request request = (MetaData.Request)frame.getMetaData();
                    assertEquals(metaData.getMethod(), request.getMethod());
                    assertEquals(metaData.getHttpURI(), request.getHttpURI());
                    for (int j = 0; j < fields.size(); ++j)
                    {
                        HttpField field = fields.getField(j);
                        assertTrue(request.getHttpFields().contains(field));
                    }
                    PriorityFrame priority = frame.getPriority();
                    assertNull(priority);
                    assertEquals(beginNanoTime, request.getBeginNanoTime());
                }
            }
        }

        assertEquals(0, trackingPool.getLeaks().size(), trackingPool.dumpLeaks());
    }

    @Test
    public void testLargeHeadersBlock() throws Exception
    {
        // Use a ByteBufferPool with a small factor, so that the accumulation buffer is not too large.
        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool(0, 128, -1));
        // A small max headers size, used for both accumulation and decoding.
        int maxHeadersSize = 512;
        Parser parser = new Parser(bufferPool, maxHeadersSize);
        // Specify headers block size to generate CONTINUATION frames.
        int maxHeadersBlockFragment = 128;
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder(), maxHeadersBlockFragment);

        int streamId = 13;
        HttpFields fields = HttpFields.build()
            .put("Accept", "text/html")
            // Large header that generates a large headers block.
            .put("User-Agent", "Jetty".repeat(256));
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.generateHeaders(accumulator, streamId, metaData, null, true);
        assertThat(accumulator.remaining(), greaterThan((long)maxHeadersSize));

        AtomicBoolean failed = new AtomicBoolean();
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failed.set(true);
            }
        });
        // Set a large max headers size for decoding, to ensure
        // the failure is due to accumulation, not decoding.
        parser.getHpackDecoder().setMaxHeaderListSize(10 * maxHeadersSize);

        try (RetainableByteBuffer buffer = accumulator.drain())
        {
            parser.parse(buffer);
            assertTrue(failed.get());
        }
    }
}
