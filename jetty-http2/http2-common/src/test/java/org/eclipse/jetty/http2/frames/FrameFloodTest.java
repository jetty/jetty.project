//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.WindowRateControl;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

public class FrameFloodTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

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
        MetaData.Request metadata = new MetaData.Request(null, (String)null, null, null, HttpVersion.HTTP_2, null, -1);
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
    public void testSettingsFrameFlood()
    {
        byte[] payload = new byte[0];
        testFrameFlood(null, frameFrom(payload.length, FrameType.SETTINGS.getType(), 0, 0, payload));
    }

    @Test
    public void testPingFrameFlood()
    {
        byte[] payload = {0, 0, 0, 0, 0, 0, 0, 0};
        testFrameFlood(null, frameFrom(payload.length, FrameType.PING.getType(), 0, 0, payload));
    }
    
    @Test
    public void testContinuationFrameFlood()
    {
        int streamId = 13;
        byte[] headersPayload = new byte[0];
        byte[] headersBytes = frameFrom(headersPayload.length, FrameType.HEADERS.getType(), 0, streamId, headersPayload);
        byte[] continuationPayload = new byte[0];
        testFrameFlood(headersBytes, frameFrom(continuationPayload.length, FrameType.CONTINUATION.getType(), 0, streamId, continuationPayload));
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
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failed.set(true);
            }
        }, 4096, 8192, new WindowRateControl(8, Duration.ofSeconds(1)));
        parser.init(UnaryOperator.identity());

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
