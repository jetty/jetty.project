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

package org.eclipse.jetty.http3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
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
    private final List<RetainableByteBuffer> accumulator = new ArrayList<>();
    private final WritableBufferPool bufferPool;
    private final StreamEndPoint endPoint;
    private final long streamType;
    private boolean initialized;
    private Throwable terminated;

    public InstructionFlusher(WritableBufferPool bufferPool, StreamEndPoint endPoint, StreamType streamType)
    {
        this.bufferPool = bufferPool;
        this.endPoint = endPoint;
        this.streamType = streamType.type();
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

        if (!initialized)
        {
            initialized = true;
            RetainableByteBuffer.Mutable buffer = bufferPool.acquire(VarLenInt.length(streamType), true);
            VarLenInt.encode(buffer, streamType);
            accumulator.add(buffer);
        }

        instructions.forEach(i -> i.encode(bufferPool, accumulator));

        if (LOG.isDebugEnabled())
            LOG.debug("writing buffers ({} bytes) on {}", accumulator.size(), this);

        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(accumulator);
        endPoint.write(false, buffer, this);
        buffer.release();

        return Action.SCHEDULED;
    }

    @Override
    protected void onSuccess()
    {
        releaseAndClear();
    }

    @Override
    protected void onFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to write buffers on {}", this, failure);

        try (AutoLock ignored = lock.lock())
        {
            terminated = failure;
            queue.clear();
        }

        releaseAndClear();

        // Cannot continue without the instruction stream, disconnect the session.
        ConnectionCloseFrame frame = new ConnectionCloseFrame(HTTP3ErrorCode.INTERNAL_ERROR.code(), "instruction_stream_failure");
        endPoint.getProtocolSession().disconnect(frame, failure, Promise.Invocable.noop());
    }

    private void releaseAndClear()
    {
        accumulator.forEach(RetainableByteBuffer::release);
        accumulator.clear();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%s", super.toString(), endPoint.getStream().getId());
    }
}
