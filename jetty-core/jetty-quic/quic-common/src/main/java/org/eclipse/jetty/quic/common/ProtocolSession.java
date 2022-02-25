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

package org.eclipse.jetty.quic.common;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.internal.QuicErrorCode;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Represents an <em>established</em> stateful connection with a remote peer for a specific QUIC connection ID.</p>
 * <p>Differently from {@link QuicSession}, which is created during the initial phases of connection establishment
 * and it is not specific to a protocol, {@link ProtocolSession} is created only when the connection is established,
 * and it is protocol specific, depending on the protocol negotiated during the connection establishment.</p>
 *
 * @see QuicSession
 */
public abstract class ProtocolSession extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolSession.class);

    private final StreamsProducer producer = new StreamsProducer();
    private final AdaptiveExecutionStrategy strategy;
    private final QuicSession session;

    public ProtocolSession(QuicSession session)
    {
        this.session = session;
        this.strategy = new AdaptiveExecutionStrategy(producer, session.getExecutor());
        addBean(strategy);
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public long getIdleTimeout()
    {
        return session.getIdleTimeout();
    }

    public int getMaxLocalStreams()
    {
        return session.getMaxLocalStreams();
    }

    public abstract Runnable getProducerTask();

    protected void produce()
    {
        // This method may be called concurrently, but the ExecutionStrategy guarantees
        // that only one active thread is the producer thread for this ProtocolSession.
        // On the server, this guarantees a "thread per active connection" model.
        if (LOG.isDebugEnabled())
            LOG.debug("processing {}", this);
        strategy.produce();
    }

    public void offer(Runnable task, boolean dispatch)
    {
        producer.offer(task);
        if (dispatch)
            strategy.dispatch();
        else
            strategy.produce();
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
        for (long writableStreamId : writableStreamIds)
        {
            onWritable(writableStreamId);
        }
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
        boolean result = false;
        for (long readableStreamId : readableStreamIds)
        {
            // Bit-wise "or" because we do not want onReadable() to be short-circuited.
            result |= onReadable(readableStreamId);
        }
        return result;
    }

    protected abstract boolean onReadable(long readableStreamId);

    public void openProtocolEndPoint(QuicStreamEndPoint endPoint)
    {
        Connection connection = getQuicSession().newConnection(endPoint);
        endPoint.setConnection(connection);
        endPoint.opened();
    }

    protected boolean onIdleTimeout()
    {
        return true;
    }

    protected void onFailure(long error, String reason, Throwable failure)
    {
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

    /**
     * <p>A factory for protocol specific instances of {@link ProtocolSession}.</p>
     */
    public interface Factory
    {
        public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context);
    }

    private class StreamsProducer implements ExecutionStrategy.Producer
    {
        private final AutoLock lock = new AutoLock();
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        public void offer(Runnable task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("enqueuing stream task {} on {}", task, ProtocolSession.this);
            try (AutoLock l = lock.lock())
            {
                tasks.offer(task);
            }
        }

        private Runnable poll()
        {
            try (AutoLock l = lock.lock())
            {
                return tasks.poll();
            }
        }

        @Override
        public Runnable produce()
        {
            Runnable task = poll();
            if (LOG.isDebugEnabled())
                LOG.debug("dequeued existing stream task {} on {}", task, ProtocolSession.this);
            if (task != null)
                return task;

            while (true)
            {
                processWritableStreams();
                boolean loop = processReadableStreams();

                task = poll();
                if (LOG.isDebugEnabled())
                    LOG.debug("dequeued produced stream task {} on {}", task, ProtocolSession.this);
                if (task != null)
                    return task;

                if (!loop)
                    break;
            }

            CloseInfo closeInfo = session.getRemoteCloseInfo();
            if (closeInfo != null)
                onClose(closeInfo.error(), closeInfo.reason());

            return null;
        }
    }
}
