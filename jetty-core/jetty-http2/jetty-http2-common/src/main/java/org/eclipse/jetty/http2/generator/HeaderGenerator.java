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

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class HeaderGenerator
{
    private int maxFrameSize = Frame.DEFAULT_MAX_SIZE;
    private final WritableBufferPool bufferPool;
    private final boolean useDirectByteBuffers;

    public HeaderGenerator(WritableBufferPool bufferPool)
    {
        this(bufferPool, true);
    }

    public HeaderGenerator(WritableBufferPool bufferPool, boolean useDirectByteBuffers)
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

    public WritableBuffer generate(FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        WritableBuffer wb = bufferPool.acquire(capacity, useDirectByteBuffers);

        wb.putInt((length & 0x00_FF_FF_FF) << 8 | (frameType.getType() & 0xFF));
        wb.put((byte)flags);
        wb.putInt(streamId);

        return wb;
    }

    public int getMaxFrameSize()
    {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        this.maxFrameSize = maxFrameSize;
    }
}
