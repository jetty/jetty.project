//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;

public class HeaderGenerator
{
    private int maxFrameSize = Frame.DEFAULT_MAX_LENGTH;

    public ByteBuffer generate(ByteBufferPool.Lease lease, FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        ByteBuffer header = lease.acquire(capacity, true);
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
