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

import java.nio.ByteBuffer;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
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
        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(), new HpackEncoder());

        final List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 8192);
        parser.init(new Parser.Listener.Adapter()
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

            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generateHeaders(lease, streamId, metaData, null, true);

            List<ByteBuffer> byteBuffers = lease.getByteBuffers();
            assertEquals(2, byteBuffers.size());

            ByteBuffer headersBody = byteBuffers.remove(1);
            int start = headersBody.position();
            int length = headersBody.remaining();
            int oneThird = length / 3;
            int lastThird = length - 2 * oneThird;

            // Adjust the length of the HEADERS frame.
            ByteBuffer headersHeader = byteBuffers.get(0);
            headersHeader.put(0, (byte)((oneThird >>> 16) & 0xFF));
            headersHeader.put(1, (byte)((oneThird >>> 8) & 0xFF));
            headersHeader.put(2, (byte)(oneThird & 0xFF));

            // Remove the END_HEADERS flag from the HEADERS header.
            headersHeader.put(4, (byte)(headersHeader.get(4) & ~Flags.END_HEADERS));

            // New HEADERS body.
            headersBody.position(start);
            headersBody.limit(start + oneThird);
            byteBuffers.add(headersBody.slice());

            // Split the rest of the HEADERS body into CONTINUATION frames.
            // First CONTINUATION header.
            byte[] continuationHeader1 = new byte[9];
            continuationHeader1[0] = (byte)((oneThird >>> 16) & 0xFF);
            continuationHeader1[1] = (byte)((oneThird >>> 8) & 0xFF);
            continuationHeader1[2] = (byte)(oneThird & 0xFF);
            continuationHeader1[3] = (byte)FrameType.CONTINUATION.getType();
            continuationHeader1[4] = Flags.NONE;
            continuationHeader1[5] = 0x00;
            continuationHeader1[6] = 0x00;
            continuationHeader1[7] = 0x00;
            continuationHeader1[8] = (byte)streamId;
            byteBuffers.add(ByteBuffer.wrap(continuationHeader1));
            // First CONTINUATION body.
            headersBody.position(start + oneThird);
            headersBody.limit(start + 2 * oneThird);
            byteBuffers.add(headersBody.slice());
            // Second CONTINUATION header.
            byte[] continuationHeader2 = new byte[9];
            continuationHeader2[0] = (byte)((lastThird >>> 16) & 0xFF);
            continuationHeader2[1] = (byte)((lastThird >>> 8) & 0xFF);
            continuationHeader2[2] = (byte)(lastThird & 0xFF);
            continuationHeader2[3] = (byte)FrameType.CONTINUATION.getType();
            continuationHeader2[4] = Flags.END_HEADERS;
            continuationHeader2[5] = 0x00;
            continuationHeader2[6] = 0x00;
            continuationHeader2[7] = 0x00;
            continuationHeader2[8] = (byte)streamId;
            byteBuffers.add(ByteBuffer.wrap(continuationHeader2));
            headersBody.position(start + 2 * oneThird);
            headersBody.limit(start + length);
            byteBuffers.add(headersBody.slice());

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            HeadersFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertTrue(frame.isEndStream());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            assertEquals(metaData.getMethod(), request.getMethod());
            assertEquals(metaData.getURI(), request.getURI());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                assertTrue(request.getFields().contains(field));
            }
            PriorityFrame priority = frame.getPriority();
            assertNull(priority);
        }
    }

    @Test
    public void testBeginNanoTime() throws Exception
    {
        ByteBufferPool bufferPool = new MappedByteBufferPool(128);
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(), new HpackEncoder());

        final List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener.Adapter()
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

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(bufferPool);
        generator.generateHeaders(lease, streamId, metaData, null, true);

        List<ByteBuffer> byteBuffers = lease.getByteBuffers();
        assertEquals(2, byteBuffers.size());

        ByteBuffer headersBody = byteBuffers.remove(1);
        int start = headersBody.position();
        int length = headersBody.remaining();
        int firstHalf = length / 2;
        int lastHalf = length - firstHalf;

        // Adjust the length of the HEADERS frame.
        ByteBuffer headersHeader = byteBuffers.get(0);
        headersHeader.put(0, (byte)((firstHalf >>> 16) & 0xFF));
        headersHeader.put(1, (byte)((firstHalf >>> 8) & 0xFF));
        headersHeader.put(2, (byte)(firstHalf & 0xFF));

        // Remove the END_HEADERS flag from the HEADERS header.
        headersHeader.put(4, (byte)(headersHeader.get(4) & ~Flags.END_HEADERS));

        // New HEADERS body.
        headersBody.position(start);
        headersBody.limit(start + firstHalf);
        byteBuffers.add(headersBody.slice());

        // Split the rest of the HEADERS body into a CONTINUATION frame.
        byte[] continuationHeader = new byte[9];
        continuationHeader[0] = (byte)((lastHalf >>> 16) & 0xFF);
        continuationHeader[1] = (byte)((lastHalf >>> 8) & 0xFF);
        continuationHeader[2] = (byte)(lastHalf & 0xFF);
        continuationHeader[3] = (byte)FrameType.CONTINUATION.getType();
        continuationHeader[4] = Flags.END_HEADERS;
        continuationHeader[5] = 0x00;
        continuationHeader[6] = 0x00;
        continuationHeader[7] = 0x00;
        continuationHeader[8] = (byte)streamId;
        byteBuffers.add(ByteBuffer.wrap(continuationHeader));
        // CONTINUATION body.
        headersBody.position(start + firstHalf);
        headersBody.limit(start + length);
        byteBuffers.add(headersBody.slice());

        assertEquals(4, byteBuffers.size());
        parser.parse(byteBuffers.get(0));
        long beginNanoTime = parser.getBeginNanoTime();
        parser.parse(byteBuffers.get(1));
        parser.parse(byteBuffers.get(2));
        parser.parse(byteBuffers.get(3));

        assertEquals(1, frames.size());
        HeadersFrame frame = frames.get(0);
        assertEquals(streamId, frame.getStreamId());
        assertTrue(frame.isEndStream());
        MetaData.Request request = (MetaData.Request)frame.getMetaData();
        assertEquals(metaData.getMethod(), request.getMethod());
        assertEquals(metaData.getURIString(), request.getURIString());
        for (int i = 0; i < fields.size(); ++i)
        {
            HttpField field = fields.getField(i);
            assertTrue(request.getFields().contains(field));
        }
        PriorityFrame priority = frame.getPriority();
        assertNull(priority);
        assertEquals(beginNanoTime, request.getBeginNanoTime());
    }

    @Test
    public void testLargeHeadersBlock() throws Exception
    {
        // Use a ByteBufferPool with a small factor, so that the accumulation buffer is not too large.
        ByteBufferPool byteBufferPool = new MappedByteBufferPool(128);
        // A small max headers size, used for both accumulation and decoding.
        int maxHeadersSize = 512;
        Parser parser = new Parser(byteBufferPool, maxHeadersSize);
        // Specify headers block size to generate CONTINUATION frames.
        int maxHeadersBlockFragment = 128;
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(), new HpackEncoder(), maxHeadersBlockFragment);

        int streamId = 13;
        HttpFields fields = HttpFields.build()
            .put("Accept", "text/html")
            // Large header that generates a large headers block.
            .put("User-Agent", "Jetty".repeat(256));
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generateHeaders(lease, streamId, metaData, null, true);
        List<ByteBuffer> byteBuffers = lease.getByteBuffers();
        assertThat(byteBuffers.stream().mapToInt(ByteBuffer::remaining).sum(), greaterThan(maxHeadersSize));

        AtomicBoolean failed = new AtomicBoolean();
        parser.init(new Parser.Listener.Adapter()
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

        for (ByteBuffer byteBuffer : byteBuffers)
        {
            parser.parse(byteBuffer);
            if (failed.get())
                break;
        }

        assertTrue(failed.get());
    }
}
