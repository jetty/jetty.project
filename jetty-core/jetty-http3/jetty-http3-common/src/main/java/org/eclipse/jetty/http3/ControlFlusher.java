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

package org.eclipse.jetty.http3;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.generator.ControlGenerator;
import org.eclipse.jetty.http3.internal.ControlStreamConnection;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(ControlFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> queue = new ArrayDeque<>();
    private final QuicStreamEndPoint endPoint;
    private final ControlGenerator generator;
    private final RetainableByteBufferPool.Accumulator accumulator;
    private boolean initialized;
    private Throwable terminated;
    private List<Entry> entries;
    private InvocationType invocationType = InvocationType.NON_BLOCKING;

    public ControlFlusher(QuicSession session, QuicStreamEndPoint endPoint, boolean useDirectByteBuffers)
    {
        this.endPoint = endPoint;
        RetainableByteBufferPool bufferPool = session.getRetainableByteBufferPool();
        this.generator = new ControlGenerator(bufferPool, useDirectByteBuffers);
        this.accumulator = new RetainableByteBufferPool.Accumulator();
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
            generator.generate(accumulator, endPoint.getStreamId(), entry.frame, null);
            invocationType = Invocable.combine(invocationType, entry.callback.getInvocationType());
        }

        if (!initialized)
        {
            initialized = true;
            ByteBuffer buffer = ByteBuffer.allocate(VarLenInt.length(ControlStreamConnection.STREAM_TYPE));
            VarLenInt.encode(buffer, ControlStreamConnection.STREAM_TYPE);
            buffer.flip();
            accumulator.insert(0, RetainableByteBuffer.wrap(buffer));
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
            LOG.debug("succeeded to write {} on {}", entries, this);

        accumulator.release();

        entries.forEach(e -> e.callback.succeeded());
        entries.clear();

        invocationType = InvocationType.NON_BLOCKING;

        super.succeeded();
    }

    @Override
    protected void onCompleteFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to write {} on {}", entries, this, failure);

        accumulator.release();

        List<Entry> allEntries = new ArrayList<>(entries);
        entries.clear();
        try (AutoLock ignored = lock.lock())
        {
            terminated = failure;
            allEntries.addAll(queue);
            queue.clear();
        }

        allEntries.forEach(e -> e.callback.failed(failure));

        long error = HTTP3ErrorCode.INTERNAL_ERROR.code();
        endPoint.close(error, failure);

        // Cannot continue without the control stream, close the session.
        endPoint.getQuicSession().getProtocolSession().outwardClose(error, "control_stream_failure");
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%s", super.toString(), endPoint.getStreamId());
    }

    private record Entry(Frame frame, Callback callback)
    {
    }
}
