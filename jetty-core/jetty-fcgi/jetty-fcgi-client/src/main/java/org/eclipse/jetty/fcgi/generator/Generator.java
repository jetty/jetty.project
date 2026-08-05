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

package org.eclipse.jetty.fcgi.generator;

import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class Generator
{
    public static final int MAX_CONTENT_LENGTH = 0xFF_FF;

    private final WritableBufferPool bufferPool;
    private final boolean useDirectByteBuffers;

    public Generator(WritableBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        this.bufferPool = bufferPool;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    public WritableBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    protected void generateContent(List<RetainableByteBuffer> accumulator, int id, RetainableByteBuffer content, boolean lastContent, FCGI.FrameType frameType)
    {
        id &= 0xFF_FF;

        content = Objects.requireNonNullElse(content, RetainableByteBuffer.empty());
        long contentLength = content.remaining();

        while (contentLength > 0 || lastContent)
        {
            RetainableByteBuffer.Mutable buffer = getBufferPool().acquire(8, isUseDirectByteBuffers());

            // Generate the frame header.
            buffer.put((byte)0x01);
            buffer.put((byte)frameType.code);
            buffer.putShort((short)id);
            long length = Math.min(MAX_CONTENT_LENGTH, contentLength);
            buffer.putShort((short)length);
            buffer.putShort((short)0);
            accumulator.add(buffer);

            if (contentLength == 0)
                break;

            // Slice the content to avoid copying.
            RetainableByteBuffer slice = content.sliceAndConsume(length);
            contentLength -= length;
            accumulator.add(slice);
        }
    }
}
