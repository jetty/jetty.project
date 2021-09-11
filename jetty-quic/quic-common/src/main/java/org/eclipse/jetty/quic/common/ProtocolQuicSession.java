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

package org.eclipse.jetty.quic.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ProtocolQuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolQuicSession.class);

    private final AtomicLong active = new AtomicLong();
    private final QuicSession session;

    public ProtocolQuicSession(QuicSession session)
    {
        this.session = session;
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public abstract void onOpen();

    public void process()
    {
        if (active.getAndIncrement() == 0)
        {
            session.getExecutor().execute(() ->
            {
                while (true)
                {
                    processWritableStreams();
                    if (processReadableStreams())
                        continue;
                    // Exit if did not process any stream and we are idle.
                    if (active.decrementAndGet() == 0)
                        break;
                }
            });
        }
    }

    protected QuicStreamEndPoint getStreamEndPoint(long streamId)
    {
        return session.getStreamEndPoint(streamId);
    }

    protected QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId, Consumer<QuicStreamEndPoint> consumer)
    {
        return session.getOrCreateStreamEndPoint(streamId, consumer);
    }

    private void processWritableStreams()
    {
        List<Long> writableStreamIds = session.getWritableStreamIds();
        if (LOG.isDebugEnabled())
            LOG.debug("writable stream ids: {}", writableStreamIds);
        writableStreamIds.forEach(this::onWritable);
    }

    protected void onWritable(long writableStreamId)
    {
        // For both client and server, we only need a get-only semantic in case of writes.
        QuicStreamEndPoint streamEndPoint = session.getStreamEndPoint(writableStreamId);
        if (LOG.isDebugEnabled())
            LOG.debug("stream {} selected endpoint for write: {}", writableStreamId, streamEndPoint);
        if (streamEndPoint != null)
            streamEndPoint.onWritable();
    }

    private boolean processReadableStreams()
    {
        List<Long> readableStreamIds = session.getReadableStreamIds();
        if (LOG.isDebugEnabled())
            LOG.debug("readable stream ids: {}", readableStreamIds);
        readableStreamIds.forEach(this::onReadable);
        return !readableStreamIds.isEmpty();
    }

    protected abstract void onReadable(long readableStreamId);

    public interface Factory
    {
        public ProtocolQuicSession newProtocolQuicSession(QuicSession quicSession, Map<String, Object> context);
    }
}
