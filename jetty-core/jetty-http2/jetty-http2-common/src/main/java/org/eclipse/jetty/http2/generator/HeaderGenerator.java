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

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class HeaderGenerator
{
    private int maxFrameSize = Frame.DEFAULT_MAX_LENGTH;
    private final RetainableByteBufferPool bufferPool;
    private final boolean useDirectByteBuffers;

    public HeaderGenerator(RetainableByteBufferPool bufferPool)
    {
        this(bufferPool, true);
    }

    public HeaderGenerator(RetainableByteBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        this.bufferPool = bufferPool;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    public RetainableByteBufferPool getRetainableByteBufferPool()
    {
        return bufferPool;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    public RetainableByteBuffer generate(FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        RetainableByteBuffer buffer = getRetainableByteBufferPool().acquire(capacity, isUseDirectByteBuffers());
        ByteBuffer header = buffer.getByteBuffer();
        BufferUtil.clearToFill(header);
        header.put((byte)((length & 0x00_FF_00_00) >>> 16));
        header.put((byte)((length & 0x00_00_FF_00) >>> 8));
        header.put((byte)((length & 0x00_00_00_FF)));
        header.put((byte)frameType.getType());
        header.put((byte)flags);
        header.putInt(streamId);
        return buffer;
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
