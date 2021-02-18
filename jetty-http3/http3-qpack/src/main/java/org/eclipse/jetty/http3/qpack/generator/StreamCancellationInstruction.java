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

package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class StreamCancellationInstruction implements Instruction
{
    private final int _streamId;

    public StreamCancellationInstruction(int streamId)
    {
        _streamId = streamId;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(6, _streamId) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x40);
        NBitInteger.encode(buffer, 6, _streamId);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
