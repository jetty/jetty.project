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

package org.eclipse.jetty.http3.qpack.internal.instruction;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

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
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitIntegerEncoder.octectsNeeded(5, _index) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x00);
        NBitIntegerEncoder.encode(buffer, 5, _index);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
