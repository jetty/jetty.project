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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class Generator
{
    private final ByteBufferPool byteBufferPool;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public Result generateContent(int streamId, int paddingLength, ByteBuffer data, boolean last, boolean compress)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        // Leave space for at least one byte of content.
        if (paddingLength > DataFrame.MAX_LENGTH - 3)
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);

        int paddingBytes = paddingLength > 0xFF ? 2 : paddingLength > 0 ? 1 : 0;

        // TODO: here we should compress the data, and then reason on the data length !

        int dataLength = data.remaining();

        Result result = new Result(byteBufferPool);

        // Can we fit just one frame ?
        if (dataLength + paddingBytes + paddingLength <= DataFrame.MAX_LENGTH)
        {
            generateFrame(result, streamId, paddingBytes, paddingLength, data, last, compress);
        }
        else
        {
            int dataBytesPerFrame = DataFrame.MAX_LENGTH - paddingBytes - paddingLength;
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
                generateFrame(result, streamId, paddingBytes, paddingLength, slice, i == frames && last, compress);
            }
        }
        return result;
    }

    private void generateFrame(Result result, int streamId, int paddingBytes, int paddingLength, ByteBuffer data, boolean last, boolean compress)
    {
        ByteBuffer header = byteBufferPool.acquire(8 + paddingBytes, true);
        BufferUtil.clearToFill(header);

        int length = paddingBytes + data.remaining() + paddingLength;
        header.putShort((short)length);

        // Frame type for DATA frames is 0.
        header.put((byte)0);

        int flags = 0;
        if (last)
            flags |= 0x01;
        if (paddingBytes > 0)
            flags |= 0x08;
        if (paddingBytes > 1)
            flags |= 0x10;
        if (compress)
            flags |= 0x20;
        header.put((byte)flags);

        header.putInt(streamId);

        if (paddingBytes == 2)
            header.putShort((short)paddingLength);
        else if (paddingBytes == 1)
            header.put((byte)paddingLength);

        BufferUtil.flipToFlush(header, 0);
        result.add(header, true);

        result.add(data, false);

        if (paddingBytes > 0)
        {
            ByteBuffer padding = byteBufferPool.acquire(paddingLength, true);
            BufferUtil.clearToFill(padding);
            padding.position(paddingLength);
            BufferUtil.flipToFlush(padding, 0);
            result.add(padding, true);
        }
    }

    public static class Result
    {
        private final ByteBufferPool byteBufferPool;
        private final List<ByteBuffer> buffers;
        private final List<Boolean> recycles;

        public Result(ByteBufferPool byteBufferPool)
        {
            this.byteBufferPool = byteBufferPool;
            this.buffers = new ArrayList<>();
            this.recycles = new ArrayList<>();
        }

        public void add(ByteBuffer buffer, boolean recycle)
        {
            buffers.add(buffer);
            recycles.add(recycle);
        }

        public List<ByteBuffer> getByteBuffers()
        {
            return buffers;
        }

        public Result merge(Result that)
        {
            assert byteBufferPool == that.byteBufferPool;
            buffers.addAll(that.buffers);
            recycles.addAll(that.recycles);
            return this;
        }
    }
}
