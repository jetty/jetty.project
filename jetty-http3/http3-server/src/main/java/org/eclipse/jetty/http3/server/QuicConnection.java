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

package org.eclipse.jetty.http3.server;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final ConcurrentMap<QuicheConnectionId, QuicSession> sessions = new ConcurrentHashMap<>();
    private final Connector connector;
    private final QuicheConfig quicheConfig;
    private final ByteBufferPool byteBufferPool;
    private final Flusher flusher = new Flusher();

    public QuicConnection(Connector connector, ServerDatagramEndPoint endp)
    {
        super(endp, connector.getExecutor());
        this.connector = connector;
        this.byteBufferPool = connector.getByteBufferPool();

        File[] files;
        try
        {
            SSLKeyPair keyPair;
            keyPair = new SSLKeyPair(new File("src/test/resources/keystore.p12"), "PKCS12", "storepwd".toCharArray(), "mykey", "storepwd".toCharArray());
            files = keyPair.export(new File(System.getProperty("java.io.tmpdir")));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // TODO make the QuicheConfig configurable
        quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(files[0].getPath());
        quicheConfig.setCertChainPemPath(files[1].getPath());
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
        quicheConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
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

    private Collection<String> getProtocols()
    {
        List<String> protocols = connector.getProtocols();
        protocols.add(0, "http/0.9"); // TODO this is only needed for Quiche example clients
        return protocols;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
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

                InetSocketAddress remoteAddress = ServerDatagramEndPoint.INET_ADDRESS_ARGUMENT.pop();
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
                    if (LOG.isDebugEnabled())
                        LOG.debug("no existing session with connection ID {}, trying to accept new QUIC connection", quicheConnectionId);
                    QuicheConnection quicheConnection = QuicheConnection.tryAccept(quicheConfig, remoteAddress, cipherBuffer);
                    if (quicheConnection == null)
                    {
                        ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                        int pos = BufferUtil.flipToFill(negotiationBuffer);
                        if (!QuicheConnection.negotiate(remoteAddress, cipherBuffer, negotiationBuffer))
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("QUIC connection negotiation failed, dropping packet");
                            byteBufferPool.release(negotiationBuffer);
                            continue;
                        }
                        BufferUtil.flipToFlush(negotiationBuffer, pos);

                        ServerDatagramEndPoint.INET_ADDRESS_ARGUMENT.push(remoteAddress);
                        getEndPoint().write(Callback.from(() -> byteBufferPool.release(negotiationBuffer)), negotiationBuffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("QUIC connection negotiation packet sent");
                    }
                    else
                    {
                        session = new QuicSession(connector, quicheConnectionId, quicheConnection, this, remoteAddress);
                        sessions.putIfAbsent(quicheConnectionId, session);
                        session.flush(); // send the response packet(s) that accept generated.
                        if (LOG.isDebugEnabled())
                            LOG.debug("created QUIC session {} with connection ID {}", session, quicheConnectionId);
                    }

                    // Once here, cipherBuffer has been fully consumed.
                    continue;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("packet is for existing session with connection ID {}, processing it ({} byte(s))", quicheConnectionId, cipherBuffer.remaining());
                session.process(remoteAddress, cipherBuffer);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("caught exception in onFillable loop", x);
            close();
        }
    }

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

            ServerDatagramEndPoint.INET_ADDRESS_ARGUMENT.push(entry.address);
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
