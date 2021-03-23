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

package org.eclipse.jetty.http3.common;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final ConcurrentMap<QuicheConnectionId, QuicSession> sessions = new ConcurrentHashMap<>();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicheConfig quicheConfig;
    private final Flusher flusher = new Flusher();

    protected QuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endp, QuicheConfig quicheConfig)
    {
        super(endp, executor);
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.quicheConfig = quicheConfig;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public QuicheConfig getQuicheConfig()
    {
        return quicheConfig;
    }

    void onClose(QuicheConnectionId quicheConnectionId)
    {
        sessions.remove(quicheConnectionId);
    }

    @Override
    public void close()
    {
        sessions.values().forEach(QuicSession::close);
        super.close();
    }

    @Override
    public void onFillable()
    {
        try
        {
            ByteBuffer cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            while (true)
            {
                BufferUtil.clear(cipherBuffer);
                int fill = getEndPoint().fill(cipherBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled cipher buffer with {} byte(s)", fill);
                // ServerDatagramEndPoint will only return -1 if input is shut down.
                if (fill < 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    getEndPoint().shutdownOutput();
                    return;
                }
                if (fill == 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    fillInterested();
                    return;
                }

                InetSocketAddress remoteAddress = QuicDatagramEndPoint.INET_ADDRESS_ARGUMENT.pop();
                if (LOG.isDebugEnabled())
                    LOG.debug("decoded peer IP address: {}, ciphertext packet size: {}", remoteAddress, cipherBuffer.remaining());

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
                    session = findPendingSession(remoteAddress);
                    if (session == null)
                        continue;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("packet is for existing session with connection ID {}, processing it ({} byte(s))", quicheConnectionId, cipherBuffer.remaining());
                session.process(remoteAddress, cipherBuffer);

                if (promoteSession(quicheConnectionId, remoteAddress))
                    sessions.put(quicheConnectionId, session);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("caught exception in onFillable loop", x);
            close();
        }
    }

    protected abstract QuicSession findPendingSession(InetSocketAddress remoteAddress);

    protected abstract boolean promoteSession(QuicheConnectionId quicheConnectionId, InetSocketAddress remoteAddress);

    public void write(Callback callback, InetSocketAddress remoteAddress, ByteBuffer... buffers)
    {
        flusher.offer(callback, remoteAddress, buffers);
        flusher.iterate();
    }

    private class Flusher extends IteratingCallback
    {
        private final AutoLock lock = new AutoLock();
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private Entry entry;

        public void offer(Callback callback, InetSocketAddress address, ByteBuffer[] buffers)
        {
            try (AutoLock l = lock.lock())
            {
                queue.offer(new Entry(callback, address, buffers));
            }
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

            QuicDatagramEndPoint.INET_ADDRESS_ARGUMENT.push(entry.address);
            getEndPoint().write(this, entry.buffers);
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
        protected void onCompleteFailure(Throwable cause)
        {
            QuicConnection.this.close();
        }

        private class Entry
        {
            private final Callback callback;
            private final InetSocketAddress address;
            private final ByteBuffer[] buffers;

            private Entry(Callback callback, InetSocketAddress address, ByteBuffer[] buffers)
            {
                this.callback = callback;
                this.address = address;
                this.buffers = buffers;
            }
        }
    }
}
