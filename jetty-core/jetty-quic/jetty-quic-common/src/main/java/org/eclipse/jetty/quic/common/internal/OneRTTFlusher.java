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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OneRTTFlusher extends CryptoFlusher
{
    private static final Logger LOG = LoggerFactory.getLogger(OneRTTFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final List<MaxDataEntry> maxDataEntries = new ArrayList<>();
    private final List<MaxDataEntry> maxDataProcessing = new ArrayList<>();

    OneRTTFlusher(QuicFlusher flusher)
    {
        super(flusher, EncryptionLevel.ONE_RTT);
    }

    boolean offer(QuicStream stream, long maxData)
    {
        try (var _ = lock.lock())
        {
            // TODO: check if closed/failed, etc.
            MaxDataEntry entry = new MaxDataEntry(stream, maxData, Callback.NOOP);
            boolean result = maxDataEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean process() throws Exception
    {
        try (var _ = lock.lock())
        {
            maxDataProcessing.addAll(maxDataEntries);
            maxDataEntries.clear();
        }

        QuicSession session = getQuicSession();
        for (MaxDataEntry entry : maxDataProcessing)
        {
            session.updateSendMaxData(entry.stream(), entry.maxData());
            session.notifyMaxData(entry.stream(), entry.maxData());
        }
        maxDataProcessing.clear();

        return super.process();
    }

    @Override
    long generateFrame(RetainableByteBuffer.Mutable framesAccumulator, QuicStream stream, Frame frame, long maxBytes)
    {
        return switch (frame)
        {
            case StreamFrame streamFrame ->
            {
                QuicSession session = getQuicSession();
                long sessionWindow = session.getSendMaxData(null);
                if (sessionWindow == 0)
                {
                    if (session.stall())
                    {
                        // TODO: optimize immediate generation if there is room.
                        offer(null, List.of(new DataBlockedFrame(session.getSendData(null))), NOOP/*TODO: failures*/);
                    }
                    yield 0;
                }

                long streamWindow = session.getSendMaxData(stream);
                if (streamWindow == 0)
                {
                    if (stream.stall())
                    {
                        // TODO: optimize immediate generation if there is room.
                        offer(null, List.of(new StreamDataBlockedFrame(stream.getId(), session.getSendData(stream))), NOOP/*TODO: failures*/);
                    }
                    yield 0;
                }

                long sendWindow = Math.min(sessionWindow, streamWindow);
                maxBytes = Math.min(sendWindow, maxBytes);

                long initial = streamFrame.data().size();
                long frameBytesGenerated = getFramesGenerator().generateStreamFrame(framesAccumulator, streamFrame, session.getSendData(stream), maxBytes);
                long dataBytes = initial - streamFrame.data().size();
                session.updateSendData(stream, dataBytes);

                yield frameBytesGenerated;
            }
            default -> super.generateFrame(framesAccumulator, stream, frame, maxBytes);
        };
    }

    record MaxDataEntry(QuicStream stream, long maxData, Callback callback) implements QuicFlusher.Entry
    {
    }
}
