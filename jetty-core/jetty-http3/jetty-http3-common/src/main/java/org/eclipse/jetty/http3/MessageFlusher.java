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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> entries = new ArrayDeque<>();
    private final ByteBufferPool.Accumulator accumulator;
    private final MessageGenerator generator;
    private Entry entry;

    public MessageFlusher(ByteBufferPool bufferPool, QpackEncoder encoder, boolean useDirectByteBuffers)
    {
        this.accumulator = new ByteBufferPool.Accumulator();
        this.generator = new MessageGenerator(bufferPool, encoder, useDirectByteBuffers);
    }

    public boolean offer(StreamEndPoint endPoint, Frame frame, Callback callback)
    {
        try (AutoLock ignored = lock.lock())
        {
            entries.offer(new Entry(endPoint, frame, callback));
        }
        return true;
    }

    @Override
    protected Action process()
    {
        try (AutoLock ignored = lock.lock())
        {
            entry = entries.poll();
            if (entry == null)
                return Action.IDLE;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", entry, this);

        Frame frame = entry.frame;

        long generated = generator.generate(accumulator, entry.endPoint.getStream().getId(), frame, this::onGenerateFailure);
        if (generated < 0)
            return Action.SCHEDULED;

        StreamEndPoint endPoint = entry.endPoint;
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} buffers ({} bytes) for stream #{} on {}", buffers.size(), accumulator.getTotalLength(), endPoint.getStream().getId(), this);

        endPoint.write(Frame.isLast(frame), buffers, Callback.from(entry.callback.getInvocationType(), this::onWriteSuccess, this::onWriteFailure));
        return Action.SCHEDULED;
    }

    private void onGenerateFailure(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to generate {} on {}", entry, this, cause);

        accumulator.release();

        entry.callback.failed(cause);
        entry = null;

        // Continue the iteration.
        succeeded();
    }

    private void onWriteSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to write {} on {}", entry, this);

        accumulator.release();

        entry.callback.succeeded();
        entry = null;

        succeeded();
    }

    private void onWriteFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to write {} on {}", entry, this, failure);

        accumulator.release();

        entry.callback.failed(failure);
        entry = null;

        // Failure to write to one StreamEndPoint
        // must not impact other StreamEndPoints.
        succeeded();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return entry.callback.getInvocationType();
    }

    private record Entry(StreamEndPoint endPoint, Frame frame, Callback callback)
    {
        @Override
        public String toString()
        {
            return String.format("%s#%d", frame, endPoint.getStream().getId());
        }
    }
}
