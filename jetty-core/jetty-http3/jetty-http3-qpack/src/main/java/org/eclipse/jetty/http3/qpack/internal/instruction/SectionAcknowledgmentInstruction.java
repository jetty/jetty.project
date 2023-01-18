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

package org.eclipse.jetty.http3.qpack.internal.instruction;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
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
    public void encode(RetainableByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(7, _streamId) + 1;
        RetainableByteBuffer buffer = accumulator.acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        byteBuffer.put((byte)0x80);
        NBitIntegerEncoder.encode(byteBuffer, 7, _streamId);
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
