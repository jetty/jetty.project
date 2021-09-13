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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstructionFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(InstructionFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Instruction> queue = new ArrayDeque<>();
    private final ByteBufferPool.Lease lease;
    private final QuicStreamEndPoint endPoint;
    private boolean initialized;

    public InstructionFlusher(QuicSession session, QuicStreamEndPoint endPoint)
    {
        this.lease = new ByteBufferPool.Lease(session.getByteBufferPool());
        this.endPoint = endPoint;
    }

    public void offer(List<Instruction> instructions)
    {
        try (AutoLock l = lock.lock())
        {
            queue.addAll(instructions);
        }
    }

    @Override
    protected Action process()
    {
        List<Instruction> instructions;
        try (AutoLock l = lock.lock())
        {
            if (queue.isEmpty())
                return Action.IDLE;
            instructions = new ArrayList<>(queue);
            queue.clear();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", instructions, this);

        instructions.forEach(i -> i.encode(lease));

        if (!initialized)
        {
            initialized = true;
            ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(EncoderConnection.STREAM_TYPE));
            VarLenInt.generate(buffer, EncoderConnection.STREAM_TYPE);
            buffer.flip();
            lease.insert(0, buffer, false);
        }

        List<ByteBuffer> buffers = lease.getByteBuffers();
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} buffers ({} bytes) on {}", buffers.size(), lease.getTotalLength(), this);
        endPoint.write(this, buffers.toArray(ByteBuffer[]::new));
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        lease.recycle();
        super.succeeded();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%s", super.toString(), endPoint.getStreamId());
    }
}
