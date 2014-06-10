//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class DataGenerator extends FrameGenerator
{
    public DataGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame, Callback callback)
    {
        DataFrame dataFrame = (DataFrame)frame;
        generateData(lease, dataFrame.getStreamId(), dataFrame.getData(), dataFrame.isEndStream(), false, null);
    }

    public void generateData(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last, boolean compress, byte[] paddingBytes)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        // Leave space for at least one byte of content.
        if (paddingLength > Frame.MAX_LENGTH - 3)
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);
        if (compress)
            throw new IllegalArgumentException("Data compression not supported");

        int extraPaddingBytes = paddingLength > 0xFF ? 2 : paddingLength > 0 ? 1 : 0;

        int dataLength = data.remaining();

        // Can we fit just one frame ?
        if (dataLength + extraPaddingBytes + paddingLength <= Frame.MAX_LENGTH)
        {
            generateData(lease, streamId, data, last, compress, extraPaddingBytes, paddingBytes);
        }
        else
        {
            int dataBytesPerFrame = Frame.MAX_LENGTH - extraPaddingBytes - paddingLength;
            int frames = dataLength / dataBytesPerFrame;
            if (frames * dataBytesPerFrame != dataLength)
            {
                ++frames;
            }
            int limit = data.limit();
            for (int i = 1; i <= frames; ++i)
            {
                data.limit(Math.min(dataBytesPerFrame * i, limit));
                ByteBuffer slice = data.slice();
                data.position(data.limit());
                generateData(lease, streamId, slice, i == frames && last, compress, extraPaddingBytes, paddingBytes);
            }
        }
    }

    private void generateData(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last, boolean compress, int extraPaddingBytes, byte[] paddingBytes)
    {
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        int length = extraPaddingBytes + data.remaining() + paddingLength;

        int flags = Flag.NONE;
        if (last)
            flags |= Flag.END_STREAM;
        if (extraPaddingBytes > 0)
            flags |= Flag.PADDING_LOW;
        if (extraPaddingBytes > 1)
            flags |= Flag.PADDING_HIGH;
        if (compress)
            flags |= Flag.COMPRESS;

        ByteBuffer header = generateHeader(lease, FrameType.DATA, Frame.HEADER_LENGTH + extraPaddingBytes, length, flags, streamId);

        if (extraPaddingBytes == 2)
        {
            header.putShort((short)paddingLength);
        }
        else if (extraPaddingBytes == 1)
        {
            header.put((byte)paddingLength);
        }

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);

        lease.append(data, false);

        if (paddingBytes != null)
        {
            lease.append(ByteBuffer.wrap(paddingBytes), false);
        }
    }
}
