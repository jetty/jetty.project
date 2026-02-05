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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.generator.ControlGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(ControlFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> queue = new ArrayDeque<>();
    private final StreamEndPoint endPoint;
    private final ControlGenerator generator;
    private final RetainableByteBuffer.Mutable accumulator;
    private boolean initialized;
    private Throwable terminated;
    private List<Entry> entries;
    private InvocationType invocationType = InvocationType.NON_BLOCKING;

    public ControlFlusher(ByteBufferPool byteBufferPool, StreamEndPoint endPoint, boolean useDirectByteBuffers)
    {
        this.endPoint = endPoint;
        this.generator = new ControlGenerator(byteBufferPool, useDirectByteBuffers);
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, true, -1, 0, 0);
    }

    public boolean offer(Frame frame, Callback callback)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
                queue.offer(new Entry(frame, callback));
        }
        if (closed == null)
            return true;
        callback.failed(closed);
        return false;
    }

    @Override
    protected Action process()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (queue.isEmpty())
                return Action.IDLE;
            entries = new ArrayList<>(queue);
            queue.clear();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", entries, this);

        for (Entry entry : entries)
        {
            if (!initialized)
            {
                initialized = true;
                long streamType = StreamType.CONTROL_STREAM.type();
                ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(streamType));
                VarLenInt.encode(buffer, streamType);
                buffer.flip();
                accumulator.add(buffer);
            }
            generator.generate(accumulator, endPoint.getStream().getId(), entry.frame, null);
            invocationType = Invocable.combine(invocationType, entry.callback.getInvocationType());
        }

        if (LOG.isDebugEnabled())
            LOG.debug("writing {} bytes on {}", accumulator.size(), this);
        accumulator.writeTo(endPoint, false, this);
        return Action.SCHEDULED;
    }

    @Override
    protected void onSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to write {} on {}", entries, this);

        accumulator.clear();

        entries.forEach(e -> e.callback.succeeded());
        entries.clear();

        invocationType = InvocationType.NON_BLOCKING;
    }

    @Override
    protected void onFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(failure).log("failed to write {} on {}", entries, this);

        List<Entry> allEntries = new ArrayList<>(entries);
        entries.clear();
        try (AutoLock ignored = lock.lock())
        {
            terminated = failure;
            allEntries.addAll(queue);
            queue.clear();
        }

        allEntries.forEach(e -> e.callback.failed(failure));

        // Cannot continue without the control stream, close the session.
        ConnectionCloseFrame frame = new ConnectionCloseFrame(HTTP3ErrorCode.INTERNAL_ERROR.code(), "control_stream_failure");
        endPoint.getProtocolSession().disconnect(frame, failure, Promise.Invocable.noop());
    }

    @Override
    protected void onCompleteFailure(Throwable cause)
    {
        accumulator.release();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%s", super.toString(), endPoint.getStream().getId());
    }

    private record Entry(Frame frame, Callback callback)
    {
    }
}
