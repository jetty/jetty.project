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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.WindowRateControl;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

public class FrameFloodTest
{
    private final ByteBufferPool bufferPool = new ArrayByteBufferPool();

    // Frame structure:
    // | Len0 | Len1 | Len2 | Type | Flags | StreamID0 |StreamID1 |StreamID2 |StreamID3 | Payload... |

    private byte[] frameFrom(int length, int frameType, int flags, int streamId, byte[] payload)
    {
        byte[] result = new byte[3 + 1 + 1 + 4 + payload.length];
        result[0] = (byte)((length >>> 16) & 0xFF);
        result[1] = (byte)((length >>> 8) & 0xFF);
        result[2] = (byte)(length & 0xFF);
        result[3] = (byte)frameType;
        result[4] = (byte)flags;
        result[5] = (byte)((streamId >>> 24) & 0xFF);
        result[6] = (byte)((streamId >>> 16) & 0xFF);
        result[7] = (byte)((streamId >>> 8) & 0xFF);
        result[8] = (byte)(streamId & 0xFF);
        System.arraycopy(payload, 0, result, 9, payload.length);
        return result;
    }

    @Test
    public void testDataFrameFlood()
    {
        byte[] payload = new byte[0];
        testFrameFlood(null, frameFrom(payload.length, FrameType.DATA.getType(), 0, 13, payload));
    }

    @Test
    public void testHeadersFrameFlood()
    {
        byte[] payload = new byte[0];
        testFrameFlood(null, frameFrom(payload.length, FrameType.HEADERS.getType(), Flags.END_HEADERS, 13, payload));
    }

    @Test
    public void testInvalidHeadersFrameFlood() throws Exception
    {
        // Invalid MetaData (no method, no scheme, etc).
        MetaData.Request metadata = new MetaData.Request("NULL", null, null, null, HttpVersion.HTTP_2, null, -1)
        {
            @Override
            public String getMethod()
            {
                return null;
            }
        };
        HpackEncoder encoder = new HpackEncoder();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        encoder.encode(buffer, metadata);
        buffer.flip();
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        testFrameFlood(null, frameFrom(payload.length, FrameType.HEADERS.getType(), Flags.END_HEADERS, 13, payload));
    }

    @Test
    public void testPriorityFrameFlood()
    {
        byte[] payload = new byte[]{0, 0, 0, 7, 0};
        testFrameFlood(null, frameFrom(payload.length, FrameType.PRIORITY.getType(), 0, 13, payload));
    }

    @Test
    public void testEmptySettingsFrameFlood()
    {
        byte[] payload = new byte[0];
        testFrameFlood(null, frameFrom(payload.length, FrameType.SETTINGS.getType(), 0, 0, payload));
    }

    @Test
    public void testSettingsFrameFlood()
    {
        // | Key0 | Key1 | Value0 | Value1 | Value2 | Value3 |
        byte[] payload = new byte[]{0, 8, 0, 0, 0, 1};
        testFrameFlood(null, frameFrom(payload.length, FrameType.SETTINGS.getType(), 0, 0, payload));
    }

    @Test
    public void testPingFrameFlood()
    {
        byte[] payload = {0, 0, 0, 0, 0, 0, 0, 0};
        testFrameFlood(null, frameFrom(payload.length, FrameType.PING.getType(), 0, 0, payload));
    }

    @Test
    public void testEmptyContinuationFrameFlood()
    {
        int streamId = 13;
        byte[] headersPayload = new byte[0];
        byte[] headersBytes = frameFrom(headersPayload.length, FrameType.HEADERS.getType(), 0, streamId, headersPayload);
        byte[] continuationPayload = new byte[0];
        testFrameFlood(headersBytes, frameFrom(continuationPayload.length, FrameType.CONTINUATION.getType(), 0, streamId, continuationPayload));
    }

    @Test
    public void testContinuationFrameFlood()
    {
        int streamId = 13;
        byte[] headersPayload = new byte[0];
        byte[] headersBytes = frameFrom(headersPayload.length, FrameType.HEADERS.getType(), 0, streamId, headersPayload);
        byte[] continuationPayload = new byte[1];
        testFrameFlood(headersBytes, frameFrom(continuationPayload.length, FrameType.CONTINUATION.getType(), 0, streamId, continuationPayload));
    }

    @Test
    public void testResetStreamFrameFlood()
    {
        byte[] payload = {0, 0, 0, 0};
        testFrameFlood(null, frameFrom(payload.length, FrameType.RST_STREAM.getType(), 0, 13, payload));
    }

    @Test
    public void testUnknownFrameFlood()
    {
        byte[] payload = {0, 0, 0, 0};
        testFrameFlood(null, frameFrom(payload.length, 64, 0, 0, payload));
    }

    private void testFrameFlood(byte[] preamble, byte[] bytes)
    {
        AtomicBoolean failed = new AtomicBoolean();
        Parser parser = new Parser(bufferPool, 8192, new WindowRateControl(8, Duration.ofSeconds(1)));
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failed.set(true);
            }
        });

        if (preamble != null)
        {
            ByteBuffer buffer = ByteBuffer.wrap(preamble);
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
        }

        int count = 0;
        while (!failed.get())
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
            assertThat("too many frames allowed", ++count, lessThan(1024));
        }
    }
}
