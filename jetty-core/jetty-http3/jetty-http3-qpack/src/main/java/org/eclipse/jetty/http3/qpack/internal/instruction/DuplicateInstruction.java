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

import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class DuplicateInstruction extends AbstractInstruction
{
    private final int _index;

    public DuplicateInstruction(RetainableByteBufferPool bufferPool, int index)
    {
        super(bufferPool);
        _index = index;
    }

    public int getIndex()
    {
        return _index;
    }

    @Override
    public void encode(RetainableByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(5, _index) + 1;
        RetainableByteBuffer buffer = getRetainableByteBufferPool().acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        byteBuffer.put((byte)0x00);
        NBitIntegerEncoder.encode(byteBuffer, 5, _index);
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
