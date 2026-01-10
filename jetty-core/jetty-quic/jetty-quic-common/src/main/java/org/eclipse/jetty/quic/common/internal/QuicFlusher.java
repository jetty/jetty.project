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

package org.eclipse.jetty.quic.common.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.internal.packets.PacketsGenerator;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;

public class QuicFlusher extends IteratingCallback
{
    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> entries = new ArrayDeque<>();
    private final List<Entry> processing = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private final PacketsGenerator packetGenerator;
    private final RetainableByteBuffer.Mutable accumulator;
    private final QuicSession session;

    public QuicFlusher(QuicSession session)
    {
        this.session = session;
        this.packetGenerator = new PacketsGenerator(session.getPacketNumbers(), new FramesGenerator(session.getByteBufferPool()), session.getQuicTLS());
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(session.getByteBufferPool(), session.getQuicConfiguration().isUseOutputDirectByteBuffers(), -1, 0, 0);
    }

    public boolean offer(QuicSession session, List<Frame> frames, Callback callback)
    {
        try (var _ = lock.lock())
        {
            // TODO: check if closed/failed, etc.
            entries.offer(new SessionEntry(session, frames, callback));
        }
        return true;
    }

    @Override
    protected Action process() throws Throwable
    {
        try (var _ = lock.lock())
        {
            for (Entry entry : entries)
            {
                processing.add(entry);
                frames.addAll(entry.frames());
            }
            // TODO: entries.clear()?
        }

        Packet packet = session.newPacket(frames);
        packetGenerator.generate(accumulator, packet);

        session.notifyOutgoingPacket(packet);

        accumulator.writeTo(session.getEndPoint(), false, this);
        return Action.SCHEDULED;
    }

    @Override
    protected void onSuccess()
    {
        accumulator.clear();
        processing.forEach(Entry::succeeded);
        processing.clear();
    }

    private sealed interface Entry extends Callback permits SessionEntry
    {
        List<Frame> frames();
    }

    private record SessionEntry(Session session, List<Frame> frames, Callback callback) implements Entry
    {
        @Override
        public void succeeded()
        {
            callback().succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            callback().failed(x);
        }
    }
}
