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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class Generator
{
    public static final int MAX_CONTENT_LENGTH = 0xFF_FF;

    private final ByteBufferPool bufferPool;
    private final boolean useDirectByteBuffers;

    public Generator(ByteBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        this.bufferPool = bufferPool;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    protected void generateContent(ByteBufferPool.Accumulator accumulator, int id, ByteBuffer content, boolean lastContent, FCGI.FrameType frameType)
    {
        id &= 0xFF_FF;

        int contentLength = content == null ? 0 : content.remaining();

        while (contentLength > 0 || lastContent)
        {
            RetainableByteBuffer buffer = getByteBufferPool().acquire(8, isUseDirectByteBuffers());
            accumulator.append(buffer);
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);

            // Generate the frame header.
            byteBuffer.put((byte)0x01);
            byteBuffer.put((byte)frameType.code);
            byteBuffer.putShort((short)id);
            int length = Math.min(MAX_CONTENT_LENGTH, contentLength);
            byteBuffer.putShort((short)length);
            byteBuffer.putShort((short)0);
            BufferUtil.flipToFlush(byteBuffer, 0);

            if (contentLength == 0)
                break;

            // Slice the content to avoid copying.
            int limit = content.limit();
            content.limit(content.position() + length);
            ByteBuffer slice = content.slice();
            // Don't recycle the slice.
            accumulator.append(RetainableByteBuffer.wrap(slice));
            content.position(content.limit());
            content.limit(limit);
            contentLength -= length;
        }
    }
}
