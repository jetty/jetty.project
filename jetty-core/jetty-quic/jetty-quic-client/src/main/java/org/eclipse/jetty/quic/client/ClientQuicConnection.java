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

package org.eclipse.jetty.quic.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The client specific implementation of {@link QuicConnection}.</p>
 * <p>For each {@link ClientConnector#connect(SocketAddress, Map)} operation,
 * a new {@link DatagramChannelEndPoint} is created with an associated
 * {@code ClientQuicConnection}.</p>
 */
public class ClientQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicConnection.class);

    private final Map<SocketAddress, ClientQuicSession> pendingSessions = new ConcurrentHashMap<>();
    private final ClientConnector connector;
    private final Map<String, Object> context;
    private Scheduler.Task connectTask;

    public ClientQuicConnection(ClientConnector connector, EndPoint endPoint, Map<String, Object> context)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.context = context;
    }

    @Override
    public void onOpen()
    {
        try
        {
            super.onOpen();

            QuicConfiguration quicConfiguration = (QuicConfiguration)context.get(QuicConfiguration.CONTEXT_KEY);

            List<String> protocols = quicConfiguration.getProtocols();
            if (protocols == null || protocols.isEmpty())
            {
                protocols = (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
                if (protocols == null || protocols.isEmpty())
                    throw new IllegalStateException("Missing ALPN protocols");
            }

            QuicheConfig quicheConfig = new QuicheConfig();
            quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
            quicheConfig.setDisableActiveMigration(quicConfiguration.isDisableActiveMigration());
            quicheConfig.setVerifyPeer(quicConfiguration.isVerifyPeerCertificates());
            // Idle timeouts must not be managed by Quiche.
            quicheConfig.setMaxIdleTimeout(0L);
            quicheConfig.setInitialMaxData((long)quicConfiguration.getSessionRecvWindow());
            quicheConfig.setInitialMaxStreamDataBidiLocal((long)quicConfiguration.getBidirectionalStreamRecvWindow());
            quicheConfig.setInitialMaxStreamDataBidiRemote((long)quicConfiguration.getBidirectionalStreamRecvWindow());
            quicheConfig.setInitialMaxStreamDataUni((long)quicConfiguration.getUnidirectionalStreamRecvWindow());
            quicheConfig.setInitialMaxStreamsUni((long)quicConfiguration.getMaxUnidirectionalRemoteStreams());
            quicheConfig.setInitialMaxStreamsBidi((long)quicConfiguration.getMaxBidirectionalRemoteStreams());
            quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);

            InetSocketAddress remoteAddress = (InetSocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);

            if (LOG.isDebugEnabled())
                LOG.debug("connecting to {} with protocols {}", remoteAddress, protocols);

            QuicheConnection quicheConnection = QuicheConnection.connect(quicheConfig, getEndPoint().getLocalAddress(), remoteAddress);
            ClientQuicSession session = new ClientQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, context);
            pendingSessions.put(remoteAddress, session);
            if (LOG.isDebugEnabled())
                LOG.debug("created {}", session);

            connectTask = getScheduler().schedule(() -> connectTimeout(remoteAddress), connector.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);

            // Send the packets generated by the connect.
            session.flush();

            fillInterested();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public void onFillable()
    {
        connectTask.cancel();
        super.onFillable();
    }

    private void connectTimeout(SocketAddress remoteAddress)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("connect timeout {} ms to {} on {}", connector.getConnectTimeout(), remoteAddress, this);
        close();
        outwardClose(remoteAddress, new SocketTimeoutException("connect timeout"));
    }

    @Override
    protected QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        ClientQuicSession session = pendingSessions.get(remoteAddress);
        if (session != null)
        {
            Runnable task = session.process(remoteAddress, cipherBuffer);
            session.offerTask(task);
            if (session.isConnectionEstablished())
            {
                pendingSessions.remove(remoteAddress);
                return session;
            }
        }
        return null;
    }

    @Override
    public boolean onIdleExpired()
    {
        boolean idle = isFillInterested();
        long idleTimeout = getEndPoint().getIdleTimeout();
        if (LOG.isDebugEnabled())
            LOG.debug("{} elapsed idle timeout {} ms", idle ? "processing" : "ignoring", idleTimeout);
        if (idle)
        {
            Collection<QuicSession> sessions = getQuicSessions();
            sessions.forEach(QuicSession::onIdleTimeout);
        }
        return false;
    }

    @Override
    public void outwardClose(QuicSession session, Throwable failure)
    {
        super.outwardClose(session, failure);
        SocketAddress remoteAddress = session.getRemoteAddress();
        outwardClose(remoteAddress, failure);
    }

    private void outwardClose(SocketAddress remoteAddress, Throwable failure)
    {
        if (remoteAddress != null)
        {
            if (pendingSessions.remove(remoteAddress) != null)
            {
                Promise<?> promise = (Promise<?>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
                if (promise != null)
                    promise.failed(failure);
            }
        }
        getEndPoint().close(failure);
    }
}
