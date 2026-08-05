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

package org.eclipse.jetty.http2.generator;

import java.util.List;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DataGenerator
{
    private final HeaderGenerator headerGenerator;

    public DataGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public int generate(List<RetainableByteBuffer> accumulator, DataFrame frame, int maxLength)
    {
        RetainableByteBuffer rb = frame.acquire();
        try
        {
            return generateData(accumulator, frame.getStreamId(), rb, frame.isEndStream(), maxLength);
        }
        finally
        {
            rb.release();
        }
    }

    public int generateData(List<RetainableByteBuffer> accumulator, int streamId, RetainableByteBuffer data, boolean last, int maxLength)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        long dataLength = data.remaining();
        int maxFrameSize = headerGenerator.getMaxFrameSize();
        maxLength = Math.min(maxFrameSize, maxLength);
        int length = dataLength > Integer.MAX_VALUE ? maxLength : Math.min((int)dataLength, maxLength);
        if (length == dataLength)
        {
            generateFrame(accumulator, streamId, data, last);
        }
        else
        {
            RetainableByteBuffer slice = data.slice(data.readPosition(), length);
            data.readPosition(data.readPosition() + length);
            generateFrame(accumulator, streamId, slice, false);
            slice.release();
        }
        return Frame.HEADER_LENGTH + length;
    }

    private void generateFrame(List<RetainableByteBuffer> accumulator, int streamId, RetainableByteBuffer data, boolean last)
    {
        long length = data.remaining();

        int flags = Flags.NONE;
        if (last)
            flags |= Flags.END_STREAM;

        RetainableByteBuffer.Mutable b = headerGenerator.generate(FrameType.DATA, Frame.HEADER_LENGTH, Math.toIntExact(length), flags, streamId);
        accumulator.add(b);
        data.retain();
        accumulator.add(data);
    }
}
