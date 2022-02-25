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
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.internal.generator.MessageGenerator;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
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
    private final ByteBufferPool.Lease lease;
    private final MessageGenerator generator;
    private Entry entry;

    public MessageFlusher(ByteBufferPool byteBufferPool, QpackEncoder encoder, int maxHeadersLength, boolean useDirectByteBuffers)
    {
        this.lease = new ByteBufferPool.Lease(byteBufferPool);
        this.generator = new MessageGenerator(encoder, maxHeadersLength, useDirectByteBuffers);
    }

    public boolean offer(QuicStreamEndPoint endPoint, Frame frame, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            entries.offer(new Entry(endPoint, frame, callback));
        }
        return true;
    }

    @Override
    protected Action process()
    {
        try (AutoLock l = lock.lock())
        {
            entry = entries.poll();
            if (entry == null)
                return Action.IDLE;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", entry, this);

        Frame frame = entry.frame;

        if (frame instanceof FlushFrame)
        {
            succeeded();
            return Action.SCHEDULED;
        }

        int generated = generator.generate(lease, entry.endPoint.getStreamId(), frame, this::failed);
        if (generated < 0)
            return Action.SCHEDULED;

        QuicStreamEndPoint endPoint = entry.endPoint;
        List<ByteBuffer> buffers = lease.getByteBuffers();
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} buffers ({} bytes) for stream #{} on {}", buffers.size(), lease.getTotalLength(), endPoint.getStreamId(), this);

        endPoint.write(this, buffers, Frame.isLast(frame));
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to write {} on {}", entry, this);

        lease.recycle();

        entry.callback.succeeded();
        entry = null;

        super.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to write {} on {}", entry, this, x);

        lease.recycle();

        entry.callback.failed(x);
        entry = null;

        // Continue the iteration.
        super.succeeded();
    }

    @Override
    public InvocationType getInvocationType()
    {
        return entry.callback.getInvocationType();
    }

    private static class Entry
    {
        private final QuicStreamEndPoint endPoint;
        private final Frame frame;
        private final Callback callback;

        private Entry(QuicStreamEndPoint endPoint, Frame frame, Callback callback)
        {
            this.endPoint = endPoint;
            this.frame = frame;
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return String.format("%s#%d", frame, endPoint.getStreamId());
        }
    }

    public static class FlushFrame extends Frame
    {
        public FlushFrame()
        {
            super(null);
        }
    }
}
