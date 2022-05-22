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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.internal.QuicErrorCode;
import org.eclipse.jetty.quic.quiche.QuicheConnectionId;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Connection} implementation that receives and sends datagram packets via its associated {@link DatagramChannelEndPoint}.</p>
 * <p>The received bytes are peeked to obtain the QUIC connection ID; each QUIC connection ID has an associated
 * {@link QuicSession}, and the received bytes are then passed to the {@link QuicSession} for processing.</p>
 * <p>On the receive side, one QuicConnection <em>fans-out</em> to multiple {@link QuicSession}s.</p>
 * <p>On the send side, many {@link QuicSession}s <em>fan-in</em> to one QuicConnection.</p>
 */
public abstract class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final List<QuicSession.Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<QuicheConnectionId, QuicSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final AdaptiveExecutionStrategy strategy;
    private final Flusher flusher = new Flusher();
    private final Callback fillableCallback = new FillableCallback();
    private int outputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;

    protected QuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endPoint, boolean useVirtualThreads)
    {
        super(endPoint, executor);
        if (!(endPoint instanceof DatagramChannelEndPoint))
            throw new IllegalArgumentException("EndPoint must be a " + DatagramChannelEndPoint.class.getSimpleName());
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.strategy = new AdaptiveExecutionStrategy(new QuicProducer(), getExecutor(), useVirtualThreads);
    }

    @Override
    public DatagramChannelEndPoint getEndPoint()
    {
        return (DatagramChannelEndPoint)super.getEndPoint();
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    public Collection<QuicSession> getQuicSessions()
    {
        return List.copyOf(sessions.values());
    }

    @Override
    public void addEventListener(EventListener listener)
    {
        super.addEventListener(listener);
        if (listener instanceof QuicSession.Listener)
            listeners.add((QuicSession.Listener)listener);
    }

    @Override
    public void removeEventListener(EventListener listener)
    {
        super.removeEventListener(listener);
        if (listener instanceof QuicSession.Listener)
            listeners.remove((QuicSession.Listener)listener);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        LifeCycle.start(strategy);
    }

    @Override
    public void onClose(Throwable cause)
    {
        LifeCycle.stop(strategy);
        super.onClose(cause);
    }

    @Override
    public void onFillable()
    {
        strategy.produce();
    }

    @Override
    public void fillInterested()
    {
        getEndPoint().fillInterested(fillableCallback);
    }

    @Override
    public abstract boolean onIdleExpired();

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("closing connection {}", this);
            // Propagate the close inward to the protocol-specific session.
            for (QuicSession session : sessions.values())
            {
                try
                {
                    session.inwardClose(QuicErrorCode.NO_ERROR.code(), "close");
                }
                catch (Throwable x)
                {
                    if (LOG.isTraceEnabled())
                        LOG.trace("could not close {}", session, x);
                }
            }
        }
    }

    public void outwardClose(QuicSession session, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("outward close {} on {}", session, this);
        QuicheConnectionId connectionId = session.getConnectionId();
        if (connectionId != null)
        {
            sessions.remove(connectionId);
            LifeCycle.stop(session);
        }
    }

    protected abstract QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException;

    public void write(Callback callback, SocketAddress remoteAddress, ByteBuffer... buffers)
    {
        flusher.offer(callback, remoteAddress, buffers);
    }

    private Runnable receiveAndProcess()
    {
        boolean interested = isFillInterested();
        if (LOG.isDebugEnabled())
            LOG.debug("receiveAndProcess() fillInterested={}", interested);
        if (interested)
            return null;

        ByteBuffer cipherBuffer = byteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        try
        {
            while (true)
            {
                BufferUtil.clear(cipherBuffer);
                SocketAddress remoteAddress = getEndPoint().receive(cipherBuffer);
                int fill = remoteAddress == DatagramChannelEndPoint.EOF ? -1 : cipherBuffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled cipher buffer with {} byte(s)", fill);
                // DatagramChannelEndPoint will only return -1 if input is shut down.
                if (fill < 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    getEndPoint().shutdownOutput();
                    return null;
                }
                if (fill == 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    fillInterested();
                    return null;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("peer IP address: {}, ciphertext packet size: {}", remoteAddress, cipherBuffer.remaining());

                QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(cipherBuffer);
                if (quicheConnectionId == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet contains undecipherable connection ID, dropping it");
                    continue;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("packet contains connection ID {}", quicheConnectionId);

                QuicSession session = sessions.get(quicheConnectionId);
                if (session == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet is for unknown session, trying to create a new one");
                    session = createSession(remoteAddress, cipherBuffer);
                    if (session != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("session created");
                        session.setConnectionId(quicheConnectionId);
                        session.setIdleTimeout(getEndPoint().getIdleTimeout());
                        sessions.put(quicheConnectionId, session);
                        listeners.forEach(session::addEventListener);
                        LifeCycle.start(session);

                        // Session creation may have generated a task.
                        Runnable task = session.pollTask();
                        if (LOG.isDebugEnabled())
                            LOG.debug("processing creation task {} on {}", task, session);
                        if (task != null)
                        {
                            byteBufferPool.release(cipherBuffer);
                            return task;
                        }
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("session not created");
                    }
                    continue;
                }

                Runnable task = process(session, remoteAddress, cipherBuffer);
                if (task != null)
                {
                    byteBufferPool.release(cipherBuffer);
                    return task;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("receiveAndProcess() failure", x);
            byteBufferPool.release(cipherBuffer);
            onFailure(x);
            return null;
        }
    }

    private Runnable process(QuicSession session, SocketAddress remoteAddress, ByteBuffer cipherBuffer)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("packet is for existing session {}, processing {} bytes", session, cipherBuffer.remaining());
            Runnable task = session.process(remoteAddress, cipherBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("produced session task {} on {}", task, this);
            return task;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("process failure for {}", session, x);
            byteBufferPool.release(cipherBuffer);
            session.onFailure(x);
            return null;
        }
    }

    protected void onFailure(Throwable failure)
    {
        sessions.values().forEach(session -> outwardClose(session, failure));
    }

    private class QuicProducer implements ExecutionStrategy.Producer
    {
        @Override
        public Runnable produce()
        {
            return receiveAndProcess();
        }
    }

    private class Flusher extends IteratingCallback
    {
        private final AutoLock lock = new AutoLock();
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private Entry entry;

        public void offer(Callback callback, SocketAddress address, ByteBuffer[] buffers)
        {
            try (AutoLock l = lock.lock())
            {
                queue.offer(new Entry(callback, address, buffers));
            }
            iterate();
        }

        @Override
        protected Action process()
        {
            try (AutoLock l = lock.lock())
            {
                entry = queue.poll();
            }
            if (entry == null)
                return Action.IDLE;

            getEndPoint().write(this, entry.address, entry.buffers);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            entry.callback.succeeded();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            entry.callback.failed(x);
            super.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return entry.callback.getInvocationType();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            QuicConnection.this.close();
        }

        private class Entry
        {
            private final Callback callback;
            private final SocketAddress address;
            private final ByteBuffer[] buffers;

            private Entry(Callback callback, SocketAddress address, ByteBuffer[] buffers)
            {
                this.callback = callback;
                this.address = address;
                this.buffers = buffers;
            }
        }
    }

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }
    }
}
