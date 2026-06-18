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

import java.util.List;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackContext;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public abstract class FrameGenerator
{
    private final HeaderGenerator headerGenerator;
    private final WritableBufferPool bufferPool;

    protected FrameGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
        this.bufferPool = headerGenerator == null ? WritableBufferPool.NON_POOLING : headerGenerator.getBufferPool();
    }

    public abstract int generate(List<ReadableBuffer> accumulator, Frame frame) throws HpackException;

    protected WritableBuffer generateHeader(FrameType frameType, int length, int flags, int streamId)
    {
        return headerGenerator.generate(frameType, Frame.HEADER_LENGTH + length, length, flags, streamId);
    }

    public int getMaxFrameSize()
    {
        return headerGenerator.getMaxFrameSize();
    }

    public WritableBufferPool getBufferPool()
    {
        return headerGenerator.getBufferPool();
    }

    public boolean isUseDirectByteBuffers()
    {
        return headerGenerator.isUseDirectByteBuffers();
    }

    protected ReadableBuffer encode(HpackEncoder encoder, MetaData metaData) throws HpackException
    {
        int bufferSize = encoder.getMaxHeaderListSize();
        if (bufferSize <= 0)
            bufferSize = HpackContext.DEFAULT_MAX_HEADER_LIST_SIZE;
        WritableBuffer hpacked = bufferPool.acquire(bufferSize, isUseDirectByteBuffers());
        try
        {
            encoder.encode(hpacked, metaData);
            return hpacked.toReadable();
        }
        catch (HpackException x)
        {
            hpacked.release();
            throw x;
        }
    }
}
