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

package org.eclipse.jetty.quic.quiche;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.AbstractSession;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Represents a logical connection with a remote peer, identified by a QUIC connection id.</p>
 * <p>Bytes received from a {@link QuicheConnection} in {@link #feed(SocketAddress, ByteBuffer)}
 * are passed to Quiche for processing; in turn, Quiche produces a list of QUIC stream ids that
 * have pending I/O events, either read-ready or write-ready.</p>
 * <p>On the receive side, a QuicheSession <em>fans-out</em> to multiple {@link QuicheStream}s.</p>
 * <p>On the send side, many {@link QuicheStream}s <em>fan-in</em> to a QuicheSession.</p>
 *
 * @see ProtocolSession
 */
public abstract class QuicheSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheSession.class);

    private final Map<Long, QuicheStream> streams = new ConcurrentHashMap<>();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final Quiche quiche;
    private final QuicheConnection connection;
    private final SocketAddress localAddress;
    private final SocketAddress remoteAddress;
    private final Flusher flusher;
    private final StreamsProducer producer;
    private final AdaptiveExecutionStrategy strategy;
    private QuicheConnectionId connectionId;
    private long idleTimeout;
    private boolean opened;

    public QuicheSession(Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicConfiguration configuration, Quiche quiche, QuicheConnection connection, SocketAddress localAddress, SocketAddress remoteAddress, Session.Listener listener)
    {
        super(executor, configuration, listener);
        this.scheduler = scheduler;
        this.byteBufferPool = bufferPool;
        this.quiche = quiche;
        this.connection = connection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.flusher = new Flusher(scheduler);
        this.producer = new StreamsProducer();
        this.strategy = new AdaptiveExecutionStrategy(producer, executor);
        installBean(strategy);
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    protected QuicheConnection getConnection()
    {
        return connection;
    }

    public String getNegotiatedProtocol()
    {
        return quiche.getNegotiatedProtocol();
    }

    @Override
    public String getId()
    {
        return getConnectionId().toString();
    }

    @Override
    public Stream newStream(long streamId, Stream.Listener listener)
    {
        QuicheStream stream = createLocalStream(streamId);
        stream.setListener(listener);
        return stream;
    }

    private QuicheStream createLocalStream(long streamId)
    {
        QuicheStream stream = new QuicheStream(this, streamId, true);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("created local {} on {}", stream, this);

            return stream;
        }
        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "duplicate_local_stream");
    }

    private QuicheStream createRemoteStream(long streamId)
    {
        QuicheStream stream = new QuicheStream(this, streamId, false);

        if (streams.putIfAbsent(streamId, stream) != null)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "duplicate_remote_stream");

        // The frame is not available from Quiche.
        Stream.Listener listener = notifyNewStream(null);
        stream.setListener(listener);
        stream.onNewStream(null);

        if (LOG.isDebugEnabled())
            LOG.debug("created remote {} on {}", stream, this);

        return stream;
    }

    @Override
    public QuicheStream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return List.copyOf(streams.values());
    }

    boolean remove(QuicheStream stream)
    {
        boolean removed = streams.remove(stream.getId()) != null;
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} from {}", removed, stream, this);
        return removed;
    }

    @Override
    public void maxStreams(long maxStreams, boolean bidirectional, Callback callback)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void ping(Callback callback)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void maxData(long maxData, Callback callback)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disconnect(long appError, String reason, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting {}/{} {}", appError, reason, this, failure);

        // Terminate all the streams.
        // This clears the streams map of this class.
        for (QuicheStream stream : streams.values())
        {
            // This is a session failure, there is no need to stop/reset the stream.
            stream.disconnect(false, appError, failure, Callback.NOOP);
        }

        quiche.close(appError, reason);
        flush();

        callback.completeWith(flusher.disconnect()
            .whenComplete((r, x) ->
            {
                LifeCycle.stop(this);
                emitDisconnect();
                // Propagate downwards.
                getConnection().disconnect(this, failure);
            }));
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return localAddress;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return remoteAddress;
    }

    @Override
    public long getBidirectionalLocalStreamMaxCount()
    {
        return Integer.MAX_VALUE;
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

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired for {}", getIdleTimeout(), this);
        return true;
    }

    public QuicheConnectionId getConnectionId()
    {
        return connectionId;
    }

    public void setConnectionId(QuicheConnectionId connectionId)
    {
        this.connectionId = connectionId;
    }

    public void feed(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        int remaining = cipherBuffer.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("feeding {} cipher bytes to {}", remaining, this);
        int accepted = quiche.feedCipherBytes(cipherBuffer, localAddress, remoteAddress);
        if (accepted != remaining)
            throw new IllegalStateException();
    }

    public boolean isConnectionEstablished()
    {
        return quiche.isConnectionEstablished();
    }

    /**
     * <p>Returns the peer certificates chain.</p>
     * <p>Due to current Quiche C API limitations (that the Rust version does not have),
     * only the last certificate in the chain is returned.
     * This may change in the future when the C APIs are aligned to the Rust APIs.</p>
     *
     * @return the peer certificates chain (currently only the last certificate in the chain)
     */
    @Override
    public X509Certificate[] getPeerCertificates()
    {
        try
        {
            byte[] encoded = quiche.getPeerCertificate();
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

    public void produce()
    {
        strategy.produce();
    }

    public void dispatch()
    {
        strategy.dispatch();
    }

    Throwable isReset(QuicheStream stream)
    {
        try
        {
            quiche.drainClearBytesForStream(stream.getId(), BufferUtil.EMPTY_BUFFER, new boolean[1]);
            return null;
        }
        catch (EOFException x)
        {
            return x;
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    int read(QuicheStream stream, ByteBuffer byteBuffer, boolean[] outLast) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("reading from {} on {}", stream, this);

        int filled = quiche.drainClearBytesForStream(stream.getId(), byteBuffer, outLast);

        if (LOG.isDebugEnabled())
            LOG.debug("read {} bytes last={} from {} on {}", filled, outLast[0], stream, this);

        flush();

        return filled;
    }

    protected int data(QuicheStream stream, boolean last, ByteBuffer buffer) throws IOException
    {
        return quiche.feedClearBytesForStream(stream.getId(), buffer, last);
    }

    /**
     * @param task  The task to offer to the execution strategy.
     * @param dispatch {@code true} to dispatch the task, {@code false} to produce in the calling thread.
     *                 Callers from application threads should use {@code true}, otherwise they may be arbitrarily
     *                 delayed. Callers from I/O threads should use {@code false} to avoid thread hops.
     */
    @Override
    public void offerTask(Runnable task, boolean dispatch)
    {
        producer.offer(task);
        if (dispatch)
            strategy.dispatch();
        else
            strategy.produce();
    }

    boolean isFinished(QuicheStream stream)
    {
        try
        {
            return quiche.isStreamFinished(stream.getId());
        }
        catch (Throwable x)
        {
            return true;
        }
    }

    boolean isFailed(QuicheStream stream)
    {
        try
        {
            return quiche.windowCapacity(stream.getId()) < 0;
        }
        catch (Throwable x)
        {
            return true;
        }
    }

    void shutdownStream(QuicheStream stream, boolean writeSide, long appErrorCode, Callback callback)
    {
        try
        {
            quiche.shutdownStream(stream.getId(), writeSide, appErrorCode);
            flush();
            callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    public void flush()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {}", this);
        flusher.iterate();
    }

    public boolean isOpen()
    {
        return opened;
    }

    public void open()
    {
        if (opened)
            return;
        opened = true;
        emitOpen();
    }

    private class Flusher extends IteratingCallback
    {
        private final CompletableFuture<Session> disconnect = new CompletableFuture<>();
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
                        LOG.debug("quiche timeout expired {}", QuicheSession.this);
                    quiche.onTimeout();
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-iterating after quiche timeout {}", QuicheSession.this);
                    // Do not use the timer thread to iterate.
                    getExecutor().execute(() -> iterate());
                }
            };
        }

        @Override
        protected Action process() throws IOException
        {
            cipherBuffer = getByteBufferPool().acquire(getQuicConfiguration().getOutputBufferSize(), getQuicConfiguration().isUseOutputDirectByteBuffers());
            ByteBuffer cipherByteBuffer = cipherBuffer.getByteBuffer();
            int pos = BufferUtil.flipToFill(cipherByteBuffer);
            int drained = quiche.drainCipherBytes(cipherByteBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("drained {} byte(s) of cipher bytes from {}", drained, QuicheSession.this);
            long nextTimeoutInMs = quiche.nextTimeout();
            if (LOG.isDebugEnabled())
                LOG.debug("next quiche timeout: {} ms on {}", nextTimeoutInMs, QuicheSession.this);
            if (nextTimeoutInMs < 0)
                timeout.cancel();
            else
                timeout.schedule(nextTimeoutInMs, TimeUnit.MILLISECONDS);
            if (drained == 0)
            {
                boolean connectionClosed = quiche.isConnectionClosed();
                Action action = connectionClosed ? Action.SUCCEEDED : Action.IDLE;
                if (LOG.isDebugEnabled())
                    LOG.debug("connection draining={} closed={}, action={} on {}", quiche.isDraining(), connectionClosed, action, QuicheSession.this);
                if (action == Action.IDLE)
                    cipherBuffer.release();
                return action;
            }
            BufferUtil.flipToFlush(cipherByteBuffer, pos);
            if (LOG.isDebugEnabled())
                LOG.debug("writing cipher bytes for {} on {}", remoteAddress, QuicheSession.this);
            getConnection().write(this, remoteAddress, cipherByteBuffer);
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("written cipher bytes on {}", QuicheSession.this);
            cipherBuffer.release();
        }

        @Override
        protected void onCompleteSuccess()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("connection closed {}", QuicheSession.this);
            complete(null);
        }

        @Override
        protected void onCompleteFailure(Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed to write cipher bytes, closing session on {}", QuicheSession.this, failure);
            complete(failure);
        }

        private void complete(Throwable failure)
        {
            cipherBuffer.release();
            timeout.destroy();
            quiche.dispose();
            if (failure == null)
                disconnect.complete(QuicheSession.this);
            else
                disconnect.completeExceptionally(failure);
        }

        private CompletableFuture<Session> disconnect()
        {
            return disconnect;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }

    private class StreamsProducer implements ExecutionStrategy.Producer
    {
        private final AutoLock lock = new AutoLock();
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        private void offer(Runnable task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("queuing stream task {} on {}", task, QuicheSession.this);
            try (AutoLock ignored = lock.lock())
            {
                tasks.offer(task);
            }
        }

        private Runnable poll()
        {
            try (AutoLock ignored = lock.lock())
            {
                return tasks.poll();
            }
        }

        @Override
        public Runnable produce()
        {
            Runnable task = poll();
            if (LOG.isDebugEnabled())
                LOG.debug("dequeued existing stream task {} on {}", task, QuicheSession.this);
            if (task != null)
                return task;

            List<Long> writable = quiche.writableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("writable stream ids: {} on {}", writable, QuicheSession.this);
            // Unfortunately, Quiche does not mark a stream as writable if it
            // has a pending write and received a STOP_SENDING, so we try to
            // complete the pending write by failing it if the conditions apply.
            for (QuicheStream stream : streams.values())
            {
                if (writable.contains(stream.getId()))
                    stream.resumeWrite();
                else
                    stream.tryFailWrite();
            }

            List<Long> readable = quiche.readableStreamIds();
            if (LOG.isDebugEnabled())
                LOG.debug("readable stream ids: {} on {}", readable, QuicheSession.this);
            for (Long streamId : readable)
            {
                QuicheStream stream = streams.get(streamId);
                if (stream == null)
                    stream = createRemoteStream(streamId);
                stream.readable();
            }

            task = poll();
            if (LOG.isDebugEnabled())
                LOG.debug("dequeued produced stream task {} on {}", task, QuicheSession.this);
            if (task != null)
                return task;

            Quiche.CloseInfo closeInfo = quiche.getRemoteCloseInfo();
            if (closeInfo != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("remote close {} on {}", closeInfo, QuicheSession.this);
                notifyConnectionClose(new ConnectionCloseFrame(closeInfo.error(), closeInfo.reason()));
            }

            if (LOG.isDebugEnabled())
                LOG.debug("stream task production idle on {}", QuicheSession.this);

            return null;
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
        }
    }
}
