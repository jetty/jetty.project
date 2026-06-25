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

package org.eclipse.jetty.quic.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultFlowController implements FlowController
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultFlowController.class);

    private final AtomicLong sessionRead = new AtomicLong();
    private final Map<Stream, AtomicLong> streamsRead = new ConcurrentHashMap<>();

    @Override
    public void onStreamCreated(Stream stream)
    {
        streamsRead.put(stream, new AtomicLong());
    }

    @Override
    public void onStreamTerminated(Stream stream)
    {
        streamsRead.remove(stream);
    }

    @Override
    public void onDataReceived(Stream stream)
    {
    }

    @Override
    public void onDataRead(Stream stream, long length)
    {
        // NOTE: this method is called from arbitrary threads,
        // so it may read session/stream offsets concurrently
        // with the receiver thread that updates those offsets.
        // As offsets are always increasing, the stale value
        // read here is smaller than the just updated value.
        // This means that the send of the MAX_DATA might be
        // delayed to the next read, which might cause the
        // sender to stall temporarily when these races occur.
        // This is simpler than coordinating a lock between
        // the receiver thread and the reader thread.

        QuicStream quicStream = (QuicStream)stream;
        QuicSession quicSession = quicStream.getSession();

        long sessionReadOffset = sessionRead.addAndGet(length);

        long sessionBudget = quicSession.getMaxData();
        long sessionMax = quicSession.getRecvMaxOffset();
        long sessionNewMax = sessionReadOffset + sessionBudget;
        long sessionReceived = quicSession.getRecvOffset();

        boolean sessionNeedsMore = sessionNewMax > sessionMax;
        boolean sessionHasEnough = sessionMax - sessionReceived > sessionBudget / 2;
        if (sessionNeedsMore && !sessionHasEnough)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("session unstalling read/recv/max->newMax {}/{}/{}->{} {}", sessionReadOffset, sessionReceived, sessionMax, sessionNewMax, quicSession);
            quicSession.maxData(sessionNewMax, Callback.NOOP);
        }

        AtomicLong streamRead = streamsRead.get(stream);
        if (streamRead == null)
            return;
        long streamReadOffset = streamRead.addAndGet(length);

        long streamBudget = quicStream.getMaxData();
        long streamMax = quicStream.getRecvMaxOffset();
        long streamNewMax = streamReadOffset + streamBudget;
        long streamReceived = quicStream.getRecvOffset();

        boolean streamNeedsMore = streamNewMax > streamMax;
        boolean streamHasEnough = streamMax - streamReceived > streamBudget / 2;
        if (streamNeedsMore && !streamHasEnough)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("stream unstalling read/recv/max->newMax {}/{}/{}->{} {}", streamReadOffset, streamReceived, streamMax, streamNewMax, quicStream);
            quicStream.maxData(streamNewMax, Callback.NOOP);
        }
    }

    public static class Factory implements FlowController.Factory
    {
        @Override
        public FlowController newFlowController()
        {
            return new DefaultFlowController();
        }
    }
}
