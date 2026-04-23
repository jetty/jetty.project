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
import java.util.Queue;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.internal.packets.PacketsGenerator;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PacketFlusher implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<PacketEntry> packetEntries = new ArrayDeque<>();
    private final QuicFlusher flusher;
    private PacketEntry processingPacketEntry;

    PacketFlusher(QuicFlusher flusher)
    {
        this.flusher = flusher;
    }

    boolean sendPacket(Packet packet, Callback callback)
    {
        try (var _ = lock.lock())
        {
            // TODO: check if closed/failed, etc.
            PacketEntry entry = new PacketEntry(packet, callback);
            boolean result = packetEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean process() throws Exception
    {
        boolean dataStalled;
        try (var _ = lock.lock())
        {
            processingPacketEntry = packetEntries.poll();
            if (processingPacketEntry == null)
                return false;
            dataStalled = packetEntries.isEmpty();
        }

        RetainableByteBuffer.Mutable packetAccumulator = flusher.getEncryptedBuffer();
        PacketsGenerator packetGenerator = flusher.getPacketsGenerator();
        QuicSession session = flusher.getQuicSession();
        EndPoint endPoint = session.getEndPoint();

        Packet packet = processingPacketEntry.packet();
        packetGenerator.generate(packetAccumulator, packet, null);
        session.notifyOutgoingPacket(packet);
        session.getPacketTracker().processPacketSent(session, packet, packetAccumulator.size(), dataStalled);
        if (LOG.isDebugEnabled())
            LOG.debug("writing packet {} {} to {} on {}", packet, packetAccumulator, endPoint, this);
        endPoint.write(flusher, session.getRemoteSocketAddress(), packetAccumulator.getByteBuffer());
        return true;
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write succeeded {} to {} on {}", processingPacketEntry.packet(), flusher.getQuicSession().getEndPoint(), this);
        processingPacketEntry.succeeded();
        processingPacketEntry = null;
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write failed {} to {} on {}", processingPacketEntry.packet(), flusher.getQuicSession().getEndPoint(), this, x);
        processingPacketEntry.failed(x);
        processingPacketEntry = null;
        // TODO: fail the queued entries.
    }

    record PacketEntry(Packet packet, Callback callback) implements QuicFlusher.Entry
    {
    }
}
