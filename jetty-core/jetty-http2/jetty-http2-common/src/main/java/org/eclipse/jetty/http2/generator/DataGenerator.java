//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.internal.Flags;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class DataGenerator
{
    private final HeaderGenerator headerGenerator;

    public DataGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public int generate(ByteBufferPool.Accumulator accumulator, DataFrame frame, int maxLength)
    {
        return generateData(accumulator, frame.getStreamId(), frame.getByteBuffer(), frame.isEndStream(), maxLength);
    }

    public int generateData(ByteBufferPool.Accumulator accumulator, int streamId, ByteBuffer data, boolean last, int maxLength)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int dataLength = data.remaining();
        int maxFrameSize = headerGenerator.getMaxFrameSize();
        int length = Math.min(dataLength, Math.min(maxFrameSize, maxLength));
        if (length == dataLength)
        {
            generateFrame(accumulator, streamId, data, last);
        }
        else
        {
            int limit = data.limit();
            int newLimit = data.position() + length;
            data.limit(newLimit);
            ByteBuffer slice = data.slice();
            data.position(newLimit);
            data.limit(limit);
            generateFrame(accumulator, streamId, slice, false);
        }
        return Frame.HEADER_LENGTH + length;
    }

    private void generateFrame(ByteBufferPool.Accumulator accumulator, int streamId, ByteBuffer data, boolean last)
    {
        int length = data.remaining();

        int flags = Flags.NONE;
        if (last)
            flags |= Flags.END_STREAM;

        RetainableByteBuffer header = headerGenerator.generate(FrameType.DATA, Frame.HEADER_LENGTH + length, length, flags, streamId);
        BufferUtil.flipToFlush(header.getByteBuffer(), 0);
        accumulator.append(header);
        // Skip empty data buffers.
        if (data.remaining() > 0)
            accumulator.append(RetainableByteBuffer.wrap(data));
    }
}
