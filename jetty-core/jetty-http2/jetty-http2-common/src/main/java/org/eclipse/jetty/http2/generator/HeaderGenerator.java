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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class HeaderGenerator
{
    private int maxFrameSize = Frame.DEFAULT_MAX_SIZE;
    private final ByteBufferPool bufferPool;
    private final boolean useDirectByteBuffers;

    public HeaderGenerator(ByteBufferPool bufferPool)
    {
        this(bufferPool, true);
    }

    public HeaderGenerator(ByteBufferPool bufferPool, boolean useDirectByteBuffers)
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

    public void generate(RetainableByteBuffer.Mutable accumulator, FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        accumulator.putInt((length & 0x00_FF_FF_FF) << 8 | (frameType.getType() & 0xFF));
        accumulator.put((byte)flags);
        accumulator.putInt(streamId);
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
