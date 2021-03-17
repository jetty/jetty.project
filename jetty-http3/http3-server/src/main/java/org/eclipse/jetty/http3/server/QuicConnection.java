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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
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

    private Collection<String> getProtocols()
    {
        // TODO get the protocols from the connector
        return Collections.singletonList("http/0.9");
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
            ByteBuffer cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN + ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
            while (true)
            {
                int fill = getEndPoint().fill(cipherBuffer);
                // ServerDatagramEndPoint will never return -1.
                if (fill == 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    fillInterested();
                    return;
                }

                InetSocketAddress remoteAddress = ServerDatagramEndPoint.decodeInetSocketAddress(cipherBuffer);
                QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(cipherBuffer);
                if (quicheConnectionId == null)
                {
                    BufferUtil.clear(cipherBuffer);
                    continue;
                }

                QuicSession session = sessions.get(quicheConnectionId);
                if (session == null)
                {
                    QuicheConnection quicheConnection = QuicheConnection.tryAccept(quicheConfig, remoteAddress, cipherBuffer);
                    if (quicheConnection == null)
                    {
                        ByteBuffer addressBuffer = encodeInetSocketAddress(byteBufferPool, remoteAddress);

                        ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                        int pos = BufferUtil.flipToFill(negotiationBuffer);
                        QuicheConnection.negotiate(remoteAddress, cipherBuffer, negotiationBuffer);
                        BufferUtil.flipToFlush(negotiationBuffer, pos);

                        getEndPoint().write(Callback.from(() ->
                        {
                            byteBufferPool.release(addressBuffer);
                            byteBufferPool.release(negotiationBuffer);
                        }), addressBuffer, negotiationBuffer);

                        // TODO: is cipherBuffer fully consumed here?
                    }
                    else
                    {
                        session = new QuicSession(connector, quicheConnection, this, remoteAddress);
                        sessions.putIfAbsent(quicheConnectionId, session);
                        session.flush();
                        if (LOG.isDebugEnabled())
                            LOG.debug("created QUIC session {}", session);
                    }
                    continue;
                }

                session.process(remoteAddress, cipherBuffer);
            }
        }
        catch (Throwable x)
        {
            close();
        }
    }

    public void write(Callback callback, ByteBuffer... buffers)
    {
        flusher.offer(callback, buffers);
        flusher.iterate();
    }

    static ByteBuffer encodeInetSocketAddress(ByteBufferPool byteBufferPool, InetSocketAddress remoteAddress) throws IOException
    {
        ByteBuffer addressBuffer = byteBufferPool.acquire(ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
        int pos = BufferUtil.flipToFill(addressBuffer);
        ServerDatagramEndPoint.encodeInetSocketAddress(addressBuffer, remoteAddress);
        BufferUtil.flipToFlush(addressBuffer, pos);
        return addressBuffer;
    }

    private class Flusher extends IteratingCallback
    {
        private final AutoLock lock = new AutoLock();
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private Entry entry;

        public void offer(Callback callback, ByteBuffer[] buffers)
        {
            try (AutoLock l = lock.lock())
            {
                queue.offer(new Entry(callback, buffers));
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
            // TODO: do we need to call Quiche here?
            getEndPoint().close(cause);
        }

        private class Entry
        {
            private final Callback callback;
            private final ByteBuffer[] buffers;

            private Entry(Callback callback, ByteBuffer[] buffers)
            {
                this.callback = callback;
                this.buffers = buffers;
            }
        }
    }
}
