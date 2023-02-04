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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class SetCapacityInstruction extends AbstractInstruction
{
    private final int _capacity;

    public SetCapacityInstruction(ByteBufferPool bufferPool, int capacity)
    {
        super(bufferPool);
        _capacity = capacity;
    }

    public int getCapacity()
    {
        return _capacity;
    }

    @Override
    public void encode(ByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(5, _capacity) + 1;
        RetainableByteBuffer buffer = getByteBufferPool().acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        byteBuffer.put((byte)0x20);
        NBitIntegerEncoder.encode(byteBuffer, 5, _capacity);
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
