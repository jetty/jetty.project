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

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.internal.generator.ControlGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.QuicSession;
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
    private final ByteBufferPool.Lease lease;
    private final ControlGenerator generator;
    private final EndPoint endPoint;
    private List<Entry> entries;
    private InvocationType invocationType = InvocationType.NON_BLOCKING;

    public ControlFlusher(QuicSession session, EndPoint endPoint)
    {
        this.lease = new ByteBufferPool.Lease(session.getByteBufferPool());
        this.endPoint = endPoint;
        this.generator = new ControlGenerator();
    }

    public void offer(Frame frame, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            queue.offer(new Entry(frame, callback));
        }
    }

    @Override
    protected Action process()
    {
        try (AutoLock l = lock.lock())
        {
            if (queue.isEmpty())
            {
                entries = List.of();
            }
            else
            {
                entries = new ArrayList<>(queue);
                queue.clear();
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} entries on {}", entries.size(), this);

        if (entries.isEmpty())
            return Action.IDLE;

        for (Entry entry : entries)
        {
            Frame frame = entry.frame;
            if (frame instanceof Frame.Synthetic)
                lease.append(((Frame.Synthetic)frame).getByteBuffer(), false);
            else
                generator.generate(lease, frame);
            invocationType = Invocable.combine(invocationType, entry.callback.getInvocationType());
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
        if (LOG.isDebugEnabled())
            LOG.debug("succeeded to flush {} entries on {}", entries, this);

        lease.recycle();

        entries.forEach(e -> e.callback.succeeded());
        entries.clear();

        invocationType = InvocationType.NON_BLOCKING;

        super.succeeded();
    }

    @Override
    protected void onCompleteFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to flush {} entries on {}", entries, this, failure);

        lease.recycle();

        entries.forEach(e -> e.callback.failed(failure));
        entries.clear();

        // TODO: I guess we should fail the whole connection, as we cannot proceed without the control stream.
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    private static class Entry
    {
        private final Frame frame;
        private final Callback callback;

        private Entry(Frame frame, Callback callback)
        {
            this.frame = frame;
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }
}
