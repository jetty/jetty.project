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
import org.eclipse.jetty.util.BufferUtil;

public class StreamCancellationInstruction implements Instruction
{
    private final long _streamId;

    public StreamCancellationInstruction(long streamId)
    {
        _streamId = streamId;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitIntegerEncoder.octetsNeeded(6, _streamId) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x40);
        NBitIntegerEncoder.encode(buffer, 6, _streamId);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
