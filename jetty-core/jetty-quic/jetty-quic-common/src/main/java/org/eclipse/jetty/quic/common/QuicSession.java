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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.QuicheConnectionId;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.Scheduler;
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
 *
 * @see ProtocolSession
 */
public abstract class QuicSession extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final AtomicLong[] ids = new AtomicLong[StreamType.values().length];
    private final ConcurrentMap<Long, QuicStreamEndPoint> endPoints = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicheConnection quicheConnection;
    private final QuicConnection connection;
    private final Flusher flusher;
    private SocketAddress remoteAddress;
    private volatile ProtocolSession protocolSession;
    private QuicheConnectionId quicheConnectionId;
    private long idleTimeout;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, SocketAddress remoteAddress)
    {
        this.executor = executor;
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.quicheConnection = quicheConnection;
        this.connection = connection;
        this.flusher = new Flusher(scheduler);
        installBean(flusher);
        this.remoteAddress = remoteAddress;
        Arrays.setAll(ids, i -> new AtomicLong());
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        getEventListeners().stream()
            .filter(Listener.class::isInstance)
            .map(Listener.class::cast)
            .forEach(this::notifyOpened);
    }

    private void notifyOpened(Listener listener)
    {
        try
        {
            listener.onOpened(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        getEventListeners().stream()
            .filter(Listener.class::isInstance)
            .map(Listener.class::cast)
            .forEach(this::notifyClosed);
    }

    private void notifyClosed(Listener listener)
    {
        try
        {
            listener.onClosed(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    public CompletableFuture<Void> shutdown()
    {
        ProtocolSession session = this.protocolSession;
        if (session != null)
            return session.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    public Executor getExecutor()
    {
        return executor;
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

    public int getMaxLocalStreams()
    {
        return quicheConnection.maxLocalStreams();
    }

    public String getNegotiatedProtocol()
    {
        return quicheConnection.getNegotiatedProtocol();
    }

    public QuicConnection getQuicConnection()
    {
        return connection;
    }

    public Collection<QuicStreamEndPoint> getQuicStreamEndPoints()
    {
        return List.copyOf(endPoints.values());
    }

    public CloseInfo getRemoteCloseInfo()
    {
        QuicheConnection.CloseInfo info = quicheConnection.getRemoteCloseInfo();
        if (info != null)
            return new CloseInfo(info.error(), info.reason());
        return null;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting idle timeout {} ms for {}", idleTimeout, this);
        this.idleTimeout = idleTimeout;
    }

    public boolean onIdleTimeout()
    {
        return protocolSession.onIdleTimeout();
    }

    public void onFailure(Throwable failure)
    {
        protocolSession.onFailure(QuicErrorCode.NO_ERROR.code(), "failure", failure);
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

    public void shutdownInput(long streamId, long error) throws IOException
    {
        quicheConnection.shutdownStream(streamId, false, error);
        flush();
    }

    public void shutdownOutput(long streamId, long error) throws IOException
    {
        quicheConnection.shutdownStream(streamId, true, error);
        flush();
    }

    public void remove(QuicStreamEndPoint endPoint, Throwable failure)
    {
        boolean removed = endPoints.remove(endPoint.getStreamId()) != null;
        if (removed)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("removed {} from {}", endPoint, this);
            endPoint.closed(failure);
        }
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

    public QuicheConnectionId getConnectionId()
    {
        return quicheConnectionId;
    }

    public void setConnectionId(QuicheConnectionId quicheConnectionId)
    {
        this.quicheConnectionId = quicheConnectionId;
    }

    public Runnable process(SocketAddress remoteAddress, ByteBuffer cipherBufferIn) throws IOException
    {
        // While the connection ID remains the same,
        // the remote address may change so store it again.
        this.remoteAddress = remoteAddress;

        int remaining = cipherBufferIn.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("feeding {} cipher bytes to {}", remaining, this);
        int accepted = quicheConnection.feedCipherBytes(cipherBufferIn, connection.getLocalInetSocketAddress(), remoteAddress);
        if (accepted != remaining)
            throw new IllegalStateException();

        if (isConnectionEstablished())
        {
            ProtocolSession protocol = protocolSession;
            if (protocol == null)
            {
                if (!validateNewlyEstablishedConnection())
                    return null;

                protocolSession = protocol = createProtocolSession();
                addManaged(protocol);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("processing {}", protocol);
            // Return a task because we want 1 thread per QUIC connection ID.
            return protocol.getProducerTask();
        }
        else
        {
            flush();
            return null;
        }
    }

    // TODO: this is ugly, is there a better solution?
    protected Runnable pollTask()
    {
        return null;
    }

    protected abstract ProtocolSession createProtocolSession();

    /**
     * @return true if the connection is valid, false otherwise.
     */
    protected abstract boolean validateNewlyEstablishedConnection();

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
        return endPoints.get(streamId);
    }

    public abstract Connection newConnection(QuicStreamEndPoint endPoint);

    public void flush()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {}", this);
        flusher.iterate();
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId, Consumer<QuicStreamEndPoint> consumer)
    {
        AtomicBoolean created = new AtomicBoolean();
        QuicStreamEndPoint endPoint = endPoints.computeIfAbsent(streamId, id ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("creating endpoint for stream #{} for {}", id, this);
            QuicStreamEndPoint result = newQuicStreamEndPoint(id);
            created.set(true);
            return result;
        });

        // The consumer must be executed outside the Map.compute() above,
        // since it may take a long time and it may be re-entrant, causing the
        // creation of two QuicStreamEndPoint objects for the same stream id.
        if (created.get())
            consumer.accept(endPoint);

        if (LOG.isDebugEnabled())
            LOG.debug("returning {} for {}", endPoint, this);
        return endPoint;
    }

    private QuicStreamEndPoint newQuicStreamEndPoint(long streamId)
    {
        return new QuicStreamEndPoint(getScheduler(), this, streamId);
    }

    public void inwardClose(long error, String reason)
    {
        protocolSession.inwardClose(error, reason);
    }

    public void outwardClose(long error, String reason)
    {
        boolean closed = quicheConnection.close(error, reason);
        if (LOG.isDebugEnabled())
            LOG.debug("outward closing ({}) 0x{}/{} on {}", closed, Long.toHexString(error), reason, this);
        if (closed)
        {
            // Flushing will eventually forward
            // the outward close to the connection.
            flush();
        }
    }

    private void finishOutwardClose(Throwable failure)
    {
        try
        {
            endPoints.clear();
            getQuicConnection().outwardClose(this, failure);
        }
        finally
        {
            // This call frees malloc'ed memory so make sure it always happens.
            quicheConnection.dispose();
        }
    }

    /**
     * <p>Returns the peer certificates chain.</p>
     * <p>Due to current Quiche C API limitations (that the Rust version does not have),
     * only the last certificate in the chain is returned.
     * This may change in the future when the C APIs are aligned to the Rust APIs.</p>
     *
     * @return the peer certificates chain (currently only the last certificate in the chain)
     */
    public X509Certificate[] getPeerCertificates()
    {
        try
        {
            byte[] encoded = quicheConnection.getPeerCertificate();
            if (encoded == null)
                return null;
            CertificateFactory factory = CertificateFactory.getInstance("X509");
            X509Certificate certificate = (X509Certificate)factory.generateCertificate(new ByteArrayInputStream(encoded));
            return new X509Certificate[]{certificate};
        }
        catch (CertificateException x)
        {
            return null;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("endPoints", getQuicStreamEndPoints()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[id=%s]", getClass().getSimpleName(), hashCode(), quicheConnectionId);
    }

    private class Flusher extends IteratingCallback
    {
        private final CyclicTimeout timeout;
        private RetainableByteBuffer cipherBuffer;

        public Flusher(Scheduler scheduler)
        {
            timeout = new CyclicTimeout(scheduler)
            {
                @Override
                public void onTimeoutExpired()
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("quiche timeout expired {}", QuicSession.this);
                    quicheConnection.onTimeout();
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-iterating after quiche timeout {}", QuicSession.this);
                    // Do not use the timer thread to iterate.
                    getExecutor().execute(() -> iterate());
                }
            };
        }

        @Override
        protected Action process() throws IOException
        {
            cipherBuffer = byteBufferPool.acquire(connection.getOutputBufferSize(), connection.isUseOutputDirectByteBuffers());
            ByteBuffer cipherByteBuffer = cipherBuffer.getByteBuffer();
            int pos = BufferUtil.flipToFill(cipherByteBuffer);
            int drained = quicheConnection.drainCipherBytes(cipherByteBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("drained {} byte(s) of cipher bytes from {}", drained, QuicSession.this);
            long nextTimeoutInMs = quicheConnection.nextTimeout();
            if (LOG.isDebugEnabled())
                LOG.debug("next quiche timeout: {} ms on {}", nextTimeoutInMs, QuicSession.this);
            if (nextTimeoutInMs < 0)
                timeout.cancel();
            else
                timeout.schedule(nextTimeoutInMs, TimeUnit.MILLISECONDS);
            if (drained == 0)
            {
                boolean connectionClosed = quicheConnection.isConnectionClosed();
                Action action = connectionClosed ? Action.SUCCEEDED : Action.IDLE;
                if (LOG.isDebugEnabled())
                    LOG.debug("connection draining={} closed={}, action={} on {}", quicheConnection.isDraining(), connectionClosed, action, QuicSession.this);
                if (action == Action.IDLE)
                    cipherBuffer.release();
                return action;
            }
            BufferUtil.flipToFlush(cipherByteBuffer, pos);
            if (LOG.isDebugEnabled())
                LOG.debug("writing cipher bytes for {} on {}", remoteAddress, QuicSession.this);
            connection.write(this, remoteAddress, cipherByteBuffer);
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("written cipher bytes on {}", QuicSession.this);
            cipherBuffer.release();
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
                LOG.debug("connection closed {}", QuicSession.this);
            cipherBuffer.release();
            finishOutwardClose(new ClosedChannelException());
            timeout.destroy();
        }

        @Override
        protected void onFailure(Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed to write cipher bytes, closing session on {}", QuicSession.this, failure);
            finishOutwardClose(failure);
            timeout.destroy();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            cipherBuffer.release();
        }
    }

    /**
     * <p>A listener for {@link QuicSession} events.</p>
     */
    public interface Listener extends EventListener
    {
        /**
         * <p>Callback method invoked when a {@link QuicSession} is opened.</p>
         *
         * @param session the session
         */
        public default void onOpened(QuicSession session)
        {
        }

        /**
         * <p>Callback method invoked when a {@link QuicSession} is closed.</p>
         *
         * @param session the session
         */
        public default void onClosed(QuicSession session)
        {
        }
    }
}
