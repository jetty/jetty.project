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

import java.util.List;

import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DuplicateInstruction implements Instruction
{
    private final int _index;

    public DuplicateInstruction(int index)
    {
        _index = index;
    }

    public int getIndex()
    {
        return _index;
    }

    @Override
    public void encode(WritableBufferPool byteBufferPool, List<RetainableByteBuffer> accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(5, _index);
        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(size, false);
        buffer.put((byte)0x00);
        NBitIntegerEncoder.encode(buffer, 5, _index);
        accumulator.add(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[index=%d]", TypeUtil.toShortName(getClass()), hashCode(), getIndex());
    }
}
