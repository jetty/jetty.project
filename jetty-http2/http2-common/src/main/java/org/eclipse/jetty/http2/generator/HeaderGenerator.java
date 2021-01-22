//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.io.ByteBufferPool;

public class HeaderGenerator
{
    private int maxFrameSize = Frame.DEFAULT_MAX_LENGTH;
    private final boolean useDirectByteBuffers;

    public HeaderGenerator()
    {
        this(true);
    }

    public HeaderGenerator(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    public ByteBuffer generate(ByteBufferPool.Lease lease, FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        ByteBuffer header = lease.acquire(capacity, isUseDirectByteBuffers());
        header.put((byte)((length & 0x00_FF_00_00) >>> 16));
        header.put((byte)((length & 0x00_00_FF_00) >>> 8));
        header.put((byte)((length & 0x00_00_00_FF)));
        header.put((byte)frameType.getType());
        header.put((byte)flags);
        header.putInt(streamId);
        return header;
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
