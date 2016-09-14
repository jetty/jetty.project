//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class DataGenerator
{
    private final HeaderGenerator headerGenerator;

    public DataGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public long generate(ByteBufferPool.Lease lease, DataFrame frame, int maxLength)
    {
        return generateData(lease, frame.getStreamId(), frame.getData(), frame.isEndStream(), maxLength);
    }

    public long generateData(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last, int maxLength)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int dataLength = data.remaining();
        int maxFrameSize = headerGenerator.getMaxFrameSize();
        if (dataLength <= maxLength && dataLength <= maxFrameSize)
        {
            // Single frame.
            return generateFrame(lease, streamId, data, last);
        }

        // Other cases, we need to slice the original buffer into multiple frames.

        int length = Math.min(maxLength, dataLength);
        int frames = length / maxFrameSize;
        if (frames * maxFrameSize != length)
            ++frames;

        long totalLength = 0;
        int begin = data.position();
        int end = data.limit();
        for (int i = 1; i <= frames; ++i)
        {
            int limit = begin + Math.min(maxFrameSize * i, length);
            data.limit(limit);
            ByteBuffer slice = data.slice();
            data.position(limit);
            totalLength += generateFrame(lease, streamId, slice, i == frames && last && limit == end);
        }
        data.limit(end);

        return totalLength;
    }

    private long generateFrame(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last)
    {
        int length = data.remaining();

        int flags = Flags.NONE;
        if (last)
            flags |= Flags.END_STREAM;

        ByteBuffer header = headerGenerator.generate(lease, FrameType.DATA, Frame.HEADER_LENGTH + length, length, flags, streamId);
        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);
        lease.append(data, false);

        return Frame.HEADER_LENGTH + length;
    }
}
