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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.internal.QuicErrorCode;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ProtocolSession extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolSession.class);

    private final AtomicInteger active = new AtomicInteger();
    private final QuicSession session;

    public ProtocolSession(QuicSession session)
    {
        this.session = session;
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public void process()
    {
        // This method is called by the network thread and
        // dispatches to one, per-session, processing thread.

        // The active counter counts up to 2, with the meanings:
        // 0=idle, 1=process, 2=re-process, where re-process is
        // necessary to close race between the processing thread
        // seeing active=1 and about to exit, and the network
        // thread also seeing active=1 and not dispatching,
        // leaving unprocessed data in the session.
        if (active.getAndUpdate(count -> count <= 1 ? count + 1 : count) == 0)
            session.getExecutor().execute(this::processSession);
    }

    private void processSession()
    {
        while (true)
        {
            processWritableStreams();
            if (processReadableStreams())
                continue;

            // Exit if did not process any stream and we are idle.
            if (active.decrementAndGet() == 0)
            {
                CloseInfo closeInfo = session.getRemoteCloseInfo();
                if (closeInfo != null)
                    onClose(closeInfo.error(), closeInfo.reason());
                break;
            }
        }
    }

    public QuicStreamEndPoint getStreamEndPoint(long streamId)
    {
        return session.getStreamEndPoint(streamId);
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId, Consumer<QuicStreamEndPoint> consumer)
    {
        return session.getOrCreateStreamEndPoint(streamId, consumer);
    }

    protected void processWritableStreams()
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

    protected boolean processReadableStreams()
    {
        List<Long> readableStreamIds = session.getReadableStreamIds();
        if (LOG.isDebugEnabled())
            LOG.debug("readable stream ids: {}", readableStreamIds);

        return readableStreamIds.stream()
            .map(this::onReadable)
            .reduce(false, (result, interested) -> result || interested);
    }

    protected abstract boolean onReadable(long readableStreamId);

    public void configureProtocolEndPoint(QuicStreamEndPoint endPoint)
    {
        Connection connection = getQuicSession().newConnection(endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }

    protected boolean onIdleTimeout()
    {
        return true;
    }

    public void inwardClose(long error, String reason)
    {
        outwardClose(error, reason);
    }

    public void outwardClose(long error, String reason)
    {
        getQuicSession().outwardClose(error, reason);
    }

    public CompletableFuture<Void> shutdown()
    {
        outwardClose(QuicErrorCode.NO_ERROR.code(), "shutdown");
        return CompletableFuture.completedFuture(null);
    }

    protected abstract void onClose(long error, String reason);

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), getQuicSession());
    }

    public interface Factory
    {
        public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context);
    }
}
