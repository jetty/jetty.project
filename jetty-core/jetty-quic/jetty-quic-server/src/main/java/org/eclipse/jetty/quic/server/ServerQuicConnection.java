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

package org.eclipse.jetty.quic.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicErrorCode;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.server.internal.SimpleTokenMinter;
import org.eclipse.jetty.quic.server.internal.SimpleTokenValidator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The server specific implementation of {@link QuicConnection}.</p>
 */
public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final Map<SocketAddress, InetSocketAddress> remoteSocketAddresses = new ConcurrentHashMap<>();
    private final Connector connector;
    private final ServerQuicConfiguration quicConfiguration;
    private final SessionTimeouts sessionTimeouts;
    private final InetSocketAddress inetLocalAddress;

    public ServerQuicConnection(Connector connector, ServerQuicConfiguration quicConfiguration, EndPoint endPoint)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.quicConfiguration = quicConfiguration;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
        this.inetLocalAddress = endPoint.getLocalSocketAddress() instanceof InetSocketAddress inet ? inet : new InetSocketAddress(InetAddress.getLoopbackAddress(), 443);
    }

    public Connector getQuicServerConnector()
    {
        return connector;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    private InetSocketAddress toInetSocketAddress(SocketAddress socketAddress)
    {
        if (socketAddress instanceof InetSocketAddress inet)
            return inet;
        return remoteSocketAddresses.computeIfAbsent(socketAddress, key -> new InetSocketAddress(InetAddress.getLoopbackAddress(), 0xFA93));
    }

    @Override
    protected QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        InetSocketAddress inetRemote = toInetSocketAddress(remoteAddress);
        ByteBufferPool bufferPool = getByteBufferPool();
        // TODO make the token validator configurable
        QuicheConnection quicheConnection = QuicheConnection.tryAccept(newQuicheConfig(), new SimpleTokenValidator(inetRemote), cipherBuffer, inetLocalAddress, inetRemote);
        if (quicheConnection == null)
        {
            RetainableByteBuffer negotiationBuffer = bufferPool.acquire(getOutputBufferSize(), true);
            ByteBuffer byteBuffer = negotiationBuffer.getByteBuffer();
            int pos = BufferUtil.flipToFill(byteBuffer);
            // TODO make the token minter configurable
            if (!QuicheConnection.negotiate(new SimpleTokenMinter(inetRemote), cipherBuffer, byteBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("QUIC connection negotiation failed, dropping packet");
                negotiationBuffer.release();
                return null;
            }
            BufferUtil.flipToFlush(byteBuffer, pos);

            write(Callback.from(negotiationBuffer::release), remoteAddress, byteBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("QUIC connection negotiation packet sent");
            return null;
        }
        else
        {
            if (quicConfiguration.getSslContextFactory().getNeedClientAuth())
            {
                byte[] peerCertificate = quicheConnection.getPeerCertificate();
                if (peerCertificate == null)
                {
                    ServerQuicSession session = newQuicSession(remoteAddress, quicheConnection);
                    ProtocolSession protocolSession = session.createProtocolSession();
                    protocolSession.disconnect(QuicErrorCode.CONNECTION_REFUSED.code(), "missing_client_cert");
                    // Send the response packet(s) that disconnect() generated.
                    session.flush();
                    return null;
                }
            }

            ServerQuicSession session = newQuicSession(remoteAddress, quicheConnection);
            // Send the response packet(s) that tryAccept() generated.
            session.flush();
            return session;
        }
    }

    protected ServerQuicSession newQuicSession(SocketAddress remoteAddress, QuicheConnection quicheConnection)
    {
        return new ServerQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, getQuicServerConnector());
    }

    @Override
    public InetSocketAddress getLocalInetSocketAddress()
    {
        return inetLocalAddress;
    }

    @Override
    protected Runnable process(QuicSession session, SocketAddress remoteAddress, ByteBuffer cipherBuffer)
    {
        InetSocketAddress inetRemote = toInetSocketAddress(remoteAddress);
        return super.process(session, inetRemote, cipherBuffer);
    }

    private QuicheConfig newQuicheConfig()
    {
        QuicheConfig quicheConfig = new QuicheConfig();
        Map<String, Object> implConfig = quicConfiguration.getImplementationConfiguration();
        Path privateKeyPath = (Path)implConfig.get(QuicConfiguration.PRIVATE_KEY_PEM_PATH_KEY);
        quicheConfig.setPrivKeyPemPath(privateKeyPath.toString());
        Path certificatesPath = (Path)implConfig.get(QuicConfiguration.CERTIFICATE_CHAIN_PEM_PATH_KEY);
        quicheConfig.setCertChainPemPath(certificatesPath.toString());
        Path trustedCertificatesPath = (Path)implConfig.get(QuicConfiguration.TRUSTED_CERTIFICATES_PEM_PATH_KEY);
        if (trustedCertificatesPath != null)
            quicheConfig.setTrustedCertsPemPath(trustedCertificatesPath.toString());
        SslContextFactory.Server sslContextFactory = quicConfiguration.getSslContextFactory();
        quicheConfig.setVerifyPeer(sslContextFactory.getNeedClientAuth() || sslContextFactory.getWantClientAuth());
        // Idle timeouts must not be managed by Quiche.
        quicheConfig.setMaxIdleTimeout(0L);
        quicheConfig.setInitialMaxData((long)quicConfiguration.getSessionRecvWindow());
        quicheConfig.setInitialMaxStreamDataBidiLocal((long)quicConfiguration.getBidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamDataBidiRemote((long)quicConfiguration.getBidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamDataUni((long)quicConfiguration.getUnidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamsUni((long)quicConfiguration.getMaxUnidirectionalRemoteStreams());
        quicheConfig.setInitialMaxStreamsBidi((long)quicConfiguration.getMaxBidirectionalRemoteStreams());
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);
        List<String> protocols = connector.getProtocols();
        // This is only needed for Quiche example clients.
        protocols.add(0, "http/0.9");
        quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
        return quicheConfig;
    }

    public void schedule(ServerQuicSession session)
    {
        sessionTimeouts.schedule(session);
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        // The current server architecture only has one listening
        // DatagramChannelEndPoint, so we ignore idle timeouts.
        return false;
    }

    @Override
    public void outwardClose(QuicSession session, Throwable failure)
    {
        super.outwardClose(session, failure);
        // Do nothing else, as the current architecture only has one
        // listening DatagramChannelEndPoint, so it must not be closed.
    }

    private class SessionTimeouts extends CyclicTimeouts<ServerQuicSession>
    {
        private SessionTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<ServerQuicSession> iterator()
        {
            return getQuicSessions().stream()
                .map(ServerQuicSession.class::cast)
                .iterator();
        }

        @Override
        protected boolean onExpired(ServerQuicSession session)
        {
            session.onIdleTimeout();
            // The implementation of the Iterator returned above does not support
            // removal, but the session will be removed by session.onIdleTimeout().
            return false;
        }
    }
}
