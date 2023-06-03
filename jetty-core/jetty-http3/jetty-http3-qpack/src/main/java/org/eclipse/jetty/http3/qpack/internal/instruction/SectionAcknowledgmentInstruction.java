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

package org.eclipse.jetty.http3.qpack.internal.instruction;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class SectionAcknowledgmentInstruction implements Instruction
{
    private final long _streamId;

    public SectionAcknowledgmentInstruction(long streamId)
    {
        _streamId = streamId;
    }

    public long getStreamId()
    {
        return _streamId;
    }

    @Override
    public void encode(ByteBufferPool byteBufferPool, ByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(7, _streamId);
        RetainableByteBuffer retainableByteBuffer = byteBufferPool.acquire(size, false);
        ByteBuffer buffer = retainableByteBuffer.getByteBuffer();
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x80);
        NBitIntegerEncoder.encode(buffer, 7, _streamId);
        BufferUtil.flipToFlush(buffer, 0);
        accumulator.append(retainableByteBuffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[stream=%d]", getClass().getSimpleName(), hashCode(), _streamId);
    }
}
