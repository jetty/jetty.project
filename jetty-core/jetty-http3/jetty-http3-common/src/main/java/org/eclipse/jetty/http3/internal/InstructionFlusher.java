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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: see QPACK spec "Avoiding Flow Control Deadlocks"
//  We would need to check the flow control window before writing.
//  However, if we do, then we need a mechanism to wakeup again this flusher
//  when Quiche tells us that the stream is writable again (right now we only do completeWrite()).
public class InstructionFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(InstructionFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Instruction> queue = new ArrayDeque<>();
    private final RetainableByteBufferPool bufferPool;
    private final RetainableByteBufferPool.Accumulator accumulator;
    private final QuicStreamEndPoint endPoint;
    private final long streamType;
    private boolean initialized;
    private Throwable terminated;

    public InstructionFlusher(QuicSession session, QuicStreamEndPoint endPoint, long streamType)
    {
        this.bufferPool = session.getRetainableByteBufferPool();
        this.accumulator = new RetainableByteBufferPool.Accumulator();
        this.endPoint = endPoint;
        this.streamType = streamType;
    }

    public boolean offer(List<Instruction> instructions)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
                queue.addAll(instructions);
        }
        return closed == null;
    }

    @Override
    protected Action process()
    {
        List<Instruction> instructions;
        try (AutoLock ignored = lock.lock())
        {
            if (queue.isEmpty())
                return Action.IDLE;
            instructions = new ArrayList<>(queue);
            queue.clear();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", instructions, this);

        instructions.forEach(i -> i.encode(accumulator));

        if (!initialized)
        {
            initialized = true;
            RetainableByteBuffer buffer = bufferPool.acquire(VarLenInt.length(streamType), false);
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);
            VarLenInt.encode(byteBuffer, streamType);
            byteBuffer.flip();
            accumulator.insert(0, buffer);
        }

        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} buffers ({} bytes) on {}", buffers.size(), accumulator.getTotalLength(), this);
        endPoint.write(this, buffers.toArray(ByteBuffer[]::new));
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to write {} buffers on {}", accumulator.getByteBuffers().size(), this);

        accumulator.release();

        super.succeeded();
    }

    @Override
    protected void onCompleteFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to write {} buffers on {}", accumulator.getByteBuffers().size(), this, failure);

        accumulator.release();

        try (AutoLock ignored = lock.lock())
        {
            terminated = failure;
            queue.clear();
        }

        long error = HTTP3ErrorCode.INTERNAL_ERROR.code();
        endPoint.close(error, failure);

        // Cannot continue without the instruction stream, close the session.
        endPoint.getQuicSession().getProtocolSession().outwardClose(error, "instruction_stream_failure");
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
