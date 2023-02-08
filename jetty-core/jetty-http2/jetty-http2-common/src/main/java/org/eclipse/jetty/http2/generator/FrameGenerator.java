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
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public abstract class FrameGenerator
{
    private final HeaderGenerator headerGenerator;

    protected FrameGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public abstract int generate(ByteBufferPool.Accumulator accumulator, Frame frame) throws HpackException;

    protected RetainableByteBuffer generateHeader(FrameType frameType, int length, int flags, int streamId)
    {
        return headerGenerator.generate(frameType, Frame.HEADER_LENGTH + length, length, flags, streamId);
    }

    public int getMaxFrameSize()
    {
        return headerGenerator.getMaxFrameSize();
    }

    public boolean isUseDirectByteBuffers()
    {
        return headerGenerator.isUseDirectByteBuffers();
    }

    protected RetainableByteBuffer encode(HpackEncoder encoder, MetaData metaData, int maxFrameSize) throws HpackException
    {
        RetainableByteBuffer hpacked = headerGenerator.getByteBufferPool().acquire(maxFrameSize, isUseDirectByteBuffers());
        try
        {
            ByteBuffer byteBuffer = hpacked.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);
            encoder.encode(byteBuffer, metaData);
            return hpacked;
        }
        catch (HpackException x)
        {
            hpacked.release();
            throw x;
        }
    }
}
