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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.QuicheConnectionId;
import org.eclipse.jetty.quic.quiche.ffi.LibQuiche;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Represents a logical connection with a remote peer, identified by a QUIC connection ID.</p>
 * <p>Each QuicSession maintains a number of QUIC streams, identified by their QUIC stream ID;
 * Each QUIC stream is wrapped in an {@link EndPoint}, namely {@link QuicStreamEndPoint}.</p>
 * <p>Bytes received from a {@link QuicConnection} in {@link #process(SocketAddress, ByteBuffer)}
 * are passed to Quiche for processing; in turn, Quiche produces a list of QUIC stream IDs that
 * have pending I/O events, either read-ready or write-ready.</p>
 * <p>On the receive side, a QuicSession <em>fans-out</em> to multiple {@link QuicStreamEndPoint}s.</p>
 * <p>On the send side, many {@link QuicStreamEndPoint}s <em>fan-in</em> to a QuicSession.</p>
 */
public abstract class QuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final AtomicLong[] ids = new AtomicLong[StreamType.values().length];
    private final AutoLock strategyQueueLock = new AutoLock();
    private final Queue<Runnable> strategyQueue = new ArrayDeque<>();
    private final ConcurrentMap<Long, QuicStreamEndPoint> endpoints = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicheConnection quicheConnection;
    private final QuicConnection connection;
    private final Flusher flusher;
    private final ExecutionStrategy strategy;
    private SocketAddress remoteAddress;
    private ProtocolSession protocolSession;
    private QuicheConnectionId quicheConnectionId;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, SocketAddress remoteAddress)
    {
        this.executor = executor;
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.quicheConnection = quicheConnection;
        this.connection = connection;
        this.flusher = new Flusher(scheduler);
        this.strategy = new AdaptiveExecutionStrategy(new Producer(), executor);
        this.remoteAddress = remoteAddress;
        LifeCycle.start(strategy);
        Arrays.setAll(ids, i -> new AtomicLong());
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public CloseInfo getRemoteCloseInfo()
    {
        AtomicStampedReference<String> info = quicheConnection.getRemoteCloseInfo();
        if (info != null)
            return new CloseInfo(info.getStamp(), info.getReference());
        return null;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public ProtocolSession getProtocolSession()
    {
        return protocolSession;
    }

    public String getNegotiatedProtocol()
    {
        return quicheConnection.getNegotiatedProtocol();
    }

    /**
     * @param streamType the stream type
     * @return a new stream ID for the given type
     */
    public long newStreamId(StreamType streamType)
    {
        int type = streamType.type();
        long id = ids[type].getAndIncrement();
        return (id << 2) + type;
    }

    public void onOpen()
    {
        protocolSession.onOpen();
    }

    public int fill(long streamId, ByteBuffer buffer) throws IOException
    {
        int drained = quicheConnection.drainClearBytesForStream(streamId, buffer);
        flush();
        return drained;
    }

    public int flush(long streamId, ByteBuffer buffer, boolean last) throws IOException
    {
        int flushed = quicheConnection.feedClearBytesForStream(streamId, buffer, last);
        flush();
        return flushed;
    }

    public void flushFinished(long streamId) throws IOException
    {
        quicheConnection.feedFinForStream(streamId);
        flush();
    }

    public boolean isFinished(long streamId)
    {
        return quicheConnection.isStreamFinished(streamId);
    }

    public long getWindowCapacity()
    {
        return quicheConnection.windowCapacity();
    }

    public long getWindowCapacity(long streamId) throws IOException
    {
        return quicheConnection.windowCapacity(streamId);
    }

    public void shutdownInput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, false);
    }

    public void shutdownOutput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, true);
    }

    public void onClose(long streamId)
    {
        endpoints.remove(streamId);
    }

    public SocketAddress getLocalAddress()
    {
        return connection.getEndPoint().getLocalSocketAddress();
    }

    public SocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public boolean isConnectionEstablished()
    {
        return quicheConnection.isConnectionEstablished();
    }

    public void setConnectionId(QuicheConnectionId quicheConnectionId)
    {
        this.quicheConnectionId = quicheConnectionId;
    }

    public void process(SocketAddress remoteAddress, ByteBuffer cipherBufferIn) throws IOException
    {
        // While the connection ID remains the same,
        // the remote address may change so store it again.
        this.remoteAddress = remoteAddress;

        int remaining = cipherBufferIn.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("feeding {} cipher bytes to {}", remaining, this);
        int accepted = quicheConnection.feedCipherBytes(cipherBufferIn, remoteAddress);
        if (accepted != remaining)
            throw new IllegalStateException();

        if (quicheConnection.isConnectionEstablished())
        {
            // HTTP/1.1
            // client1 -- sockEP1 -- H1Connection

            // HTTP/2
            // client1 -- sockEP1 -> H2Connection - HEADERSParser - H2Session -* RequestStreams -# HTTP Handler
            // client2 -- sockEP2 -> H2Connection - HEADERSParser - H2Session -* RequestStreams -# HTTP Handler

            // HTTP/1 on QUIC
            // client1
            //        \
            //         dataEP - QuicConnection -* QuicSession -# ProtocolSession -* RequestStreamN - HttpConnection - HTTP Handler
            //        /
            // client2

            // HTTP/3
            // client1
            //        \                                                        /- ControlStream0 - ControlParser for SETTINGS frames, etc.
            //         dataEP - QuicConnection -* QuicSession -# H3QuicSession -* RequestStreamsEP - H3Connection - HEADERSParser -# HTTP Handler
            //        /                                                        `- InstructionStream - InstructionConnection/Parser
            // client2
            // H3ProtoSession - QpackEncoder
            // H3ProtoSession - QpackDecoder
            // H3ProtoSession -* request streams

            if (protocolSession == null)
            {
                protocolSession = createProtocolSession();
                onOpen();
            }
            protocolSession.process();
        }
        else
        {
            flush();
        }
    }

    protected abstract ProtocolSession createProtocolSession();

    List<Long> getWritableStreamIds()
    {
        return quicheConnection.writableStreamIds();
    }

    List<Long> getReadableStreamIds()
    {
        return quicheConnection.readableStreamIds();
    }

    QuicStreamEndPoint getStreamEndPoint(long streamId)
    {
        return endpoints.get(streamId);
    }

    public abstract Connection newConnection(QuicStreamEndPoint endPoint);

    private void dispatch(Runnable runnable)
    {
        try (AutoLock l = strategyQueueLock.lock())
        {
            strategyQueue.offer(runnable);
        }
        strategy.dispatch();
    }

    public void flush()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {}", this);
        flusher.iterate();
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId, Consumer<QuicStreamEndPoint> consumer)
    {
        QuicStreamEndPoint endPoint = endpoints.compute(streamId, (id, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("creating endpoint for stream {} for {}", id, this);
                quicStreamEndPoint = newQuicStreamEndPoint(streamId);
                consumer.accept(quicStreamEndPoint);
            }
            return quicStreamEndPoint;
        });
        if (LOG.isDebugEnabled())
            LOG.debug("returning endpoint for stream {} for {}", streamId, this);
        return endPoint;
    }

    private QuicStreamEndPoint newQuicStreamEndPoint(long streamId)
    {
        return new QuicStreamEndPoint(getScheduler(), this, streamId);
    }

    public void close()
    {
        if (quicheConnectionId == null)
            close(new IOException("connection refused"));
        else
            close(new IOException("connection closed"));
    }

    private void close(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing {}", this);
        try
        {
            endpoints.values().forEach(QuicStreamEndPoint::close);
            endpoints.clear();
            flusher.close();
            connection.closeSession(quicheConnectionId, this, x);
            LifeCycle.stop(strategy);
        }
        finally
        {
            // This call frees malloc'ed memory so make sure it always happens.
            quicheConnection.dispose();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("closed {}", this);
    }

    public boolean close(int error, String reason)
    {
        return quicheConnection.close(error, reason);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[id=%s]", getClass().getSimpleName(), hashCode(), quicheConnectionId);
    }

    private class Flusher extends IteratingCallback
    {
        private final CyclicTimeout timeout;
        private ByteBuffer cipherBuffer;

        public Flusher(Scheduler scheduler)
        {
            timeout = new CyclicTimeout(scheduler)
            {
                @Override
                public void onTimeoutExpired()
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("quiche timeout callback called cid={}", quicheConnectionId);
                    quicheConnection.onTimeout();
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-iterating quiche after timeout cid={}", quicheConnectionId);
                    // Do not use the timer thread to iterate.
                    dispatch(() -> iterate());
                }
            };
        }

        @Override
        public void close()
        {
            super.close();
            timeout.destroy();
        }

        @Override
        protected Action process() throws IOException
        {
            // TODO make the buffer size configurable
            cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            int pos = BufferUtil.flipToFill(cipherBuffer);
            int drained = quicheConnection.drainCipherBytes(cipherBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("drained {} byte(s) of cipher text from {}", drained, this);
            long nextTimeoutInMs = quicheConnection.nextTimeout();
            if (LOG.isDebugEnabled())
                LOG.debug("next quiche timeout: {} ms", nextTimeoutInMs);
            if (nextTimeoutInMs < 0)
                timeout.cancel();
            else
                timeout.schedule(nextTimeoutInMs, TimeUnit.MILLISECONDS);
            if (drained == 0)
            {
                boolean connectionClosed = quicheConnection.isConnectionClosed();
                Action action = connectionClosed ? Action.SUCCEEDED : Action.IDLE;
                if (LOG.isDebugEnabled())
                    LOG.debug("connection closed={}, action={}", connectionClosed, action);
                return action;
            }
            BufferUtil.flipToFlush(cipherBuffer, pos);
            connection.write(this, remoteAddress, cipherBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("wrote cipher text for {}", remoteAddress);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cipher text writing succeeded");
            byteBufferPool.release(cipherBuffer);
            super.succeeded();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        protected void onCompleteSuccess()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("quiche connection is in closed state");
            QuicSession.this.close();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cipher text writing failed, closing session", cause);
            byteBufferPool.release(cipherBuffer);
            QuicSession.this.close(cause);
        }
    }

    private class Producer implements ExecutionStrategy.Producer
    {
        @Override
        public Runnable produce()
        {
            try (AutoLock l = strategyQueueLock.lock())
            {
                return strategyQueue.poll();
            }
        }
    }
}
