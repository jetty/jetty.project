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
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
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
    private final MessageGenerator generator;
    private final RetainableByteBuffer.Mutable accumulator;
    private Throwable terminated;
    private Entry entry;

    public MessageFlusher(ByteBufferPool bufferPool, QpackEncoder encoder, boolean useDirectByteBuffers)
    {
        this.generator = new MessageGenerator(bufferPool, encoder, useDirectByteBuffers);
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(bufferPool, true, -1, 0, 0);
    }

    public boolean offer(StreamEndPoint endPoint, Frame frame, Callback callback)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                entries.offer(new Entry(endPoint, frame, callback));
                return true;
            }
        }
        callback.failed(closed);
        return false;
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

        StreamEndPoint endPoint = entry.endPoint();
        long generated = generator.generate(accumulator, endPoint.getStream().getId(), frame, this::onGenerateFailure);
        if (generated < 0)
            return Action.SCHEDULED;

        if (LOG.isDebugEnabled())
            LOG.debug("writing {} bytes for stream #{} on {}", accumulator.size(), endPoint.getStream().getId(), this);

        accumulator.writeTo(endPoint, Frame.isLast(frame), Callback.from(entry.callback.getInvocationType(), this::onWriteSuccess, this::onWriteFailure));
        return Action.SCHEDULED;
    }

    private void onGenerateFailure(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(cause).log("failed to generate {} on {}", entry, this);

        entry.callback.failed(cause);
        entry = null;

        // Continue the iteration.
        succeeded();
    }

    private void onWriteSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to write {} on {}", entry, this);

        entry.callback().succeeded();
        entry = null;

        succeeded();
    }

    private void onWriteFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(failure).log("failed to write {} on {}", entry, this);

        entry.callback().failed(failure);
        entry = null;

        // Failure to write to one StreamEndPoint
        // must not impact other StreamEndPoints.
        succeeded();
    }

    @Override
    protected void onFailure(Throwable failure)
    {
        List<Entry> allEntries;
        try (AutoLock ignored = lock.lock())
        {
            terminated = failure;
            allEntries = List.copyOf(entries);
            entries.clear();
        }
        allEntries.forEach(e -> e.callback.failed(failure));
    }

    @Override
    protected void onCompleteFailure(Throwable failure)
    {
        accumulator.release();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return Objects.requireNonNullElse(entry.callback(), NOOP).getInvocationType();
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
