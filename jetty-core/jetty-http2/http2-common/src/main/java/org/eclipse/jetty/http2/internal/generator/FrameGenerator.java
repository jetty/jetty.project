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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;

public abstract class FrameGenerator
{
    private final HeaderGenerator headerGenerator;

    protected FrameGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public abstract int generate(ByteBufferPool.Lease lease, Frame frame) throws HpackException;

    protected ByteBuffer generateHeader(ByteBufferPool.Lease lease, FrameType frameType, int length, int flags, int streamId)
    {
        return headerGenerator.generate(lease, frameType, Frame.HEADER_LENGTH + length, length, flags, streamId);
    }

    public int getMaxFrameSize()
    {
        return headerGenerator.getMaxFrameSize();
    }

    public boolean isUseDirectByteBuffers()
    {
        return headerGenerator.isUseDirectByteBuffers();
    }

    protected ByteBuffer encode(HpackEncoder encoder, ByteBufferPool.Lease lease, MetaData metaData, int maxFrameSize) throws HpackException
    {
        ByteBuffer hpacked = lease.acquire(maxFrameSize, isUseDirectByteBuffers());
        try
        {
            encoder.encode(hpacked, metaData);
            return hpacked;
        }
        catch (HpackException x)
        {
            lease.release(hpacked);
            throw x;
        }
    }
}
