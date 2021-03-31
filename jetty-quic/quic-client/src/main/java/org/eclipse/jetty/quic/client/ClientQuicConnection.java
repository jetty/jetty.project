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

package org.eclipse.jetty.quic.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.QuicheConnectionId;
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

    private final Map<SocketAddress, QuicSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<String, Object> context;

    public ClientQuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endPoint, Map<String, Object> context)
    {
        super(executor, scheduler, byteBufferPool, endPoint);
        this.context = context;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();

        try
        {
            InetSocketAddress remoteAddress = (InetSocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);

            // TODO: pull the config settings from somewhere else TBD (context?)
            QuicheConfig quicheConfig = new QuicheConfig();
            List<String> protocols = destination.getOrigin().getProtocol().getProtocols();
            quicheConfig.setApplicationProtos(protocols.toArray(new String[0]));
            quicheConfig.setDisableActiveMigration(true);
            quicheConfig.setVerifyPeer(false);
            quicheConfig.setMaxIdleTimeout(5000L);
            quicheConfig.setInitialMaxData(10_000_000L);
            quicheConfig.setInitialMaxStreamDataBidiLocal(10_000_000L);
            quicheConfig.setInitialMaxStreamDataUni(10_000_000L);
            quicheConfig.setInitialMaxStreamsBidi(100L);
            quicheConfig.setInitialMaxStreamsUni(100L);

            QuicheConnection quicheConnection = QuicheConnection.connect(quicheConfig, remoteAddress);
            QuicSession session = new ClientQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, context);
            pendingSessions.put(remoteAddress, session);
            session.flush(); // send the response packet(s) that connect generated.
            if (LOG.isDebugEnabled())
                LOG.debug("created connecting QUIC session {}", session);
        }
        catch (IOException e)
        {
            throw new RuntimeIOException("Error trying to open connection", e);
        }

        fillInterested();
    }

    @Override
    protected void closeSession(QuicheConnectionId quicheConnectionId, QuicSession session, Throwable x)
    {
        super.closeSession(quicheConnectionId, session, x);

        SocketAddress remoteAddress = session.getRemoteAddress();
        if (pendingSessions.remove(remoteAddress) != null)
        {
            Promise<?> promise = (Promise<?>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
            if (promise != null)
                promise.failed(x);
        }
    }

    @Override
    protected QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        QuicSession session = pendingSessions.get(remoteAddress);
        if (session != null)
        {
            session.process(remoteAddress, cipherBuffer);
            if (session.isConnectionEstablished())
            {
                pendingSessions.remove(remoteAddress);
                session.onOpen();
                return session;
            }
        }
        return null;
    }
}
