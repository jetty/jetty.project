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

package org.eclipse.jetty.quic.quiche.client.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.quiche.PemPaths;
import org.eclipse.jetty.quic.quiche.Quiche;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.QuicheConnectionId;
import org.eclipse.jetty.quic.quiche.QuicheSession;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The client specific implementation of {@link QuicheConnection}.</p>
 * <p>For each {@link ClientConnector#connect(SocketAddress, Map)} operation,
 * a new {@link DatagramChannelEndPoint} is created with an associated
 * {@code ClientQuicheConnection}.</p>
 * <p>This class manages just one {@link ClientQuicheSession}.</p>
 */
public class ClientQuicheConnection extends QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicheConnection.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final ClientConnector connector;
    private final SslContextFactory.Client sslContextFactory;
    private final QuicheClientQuicConfiguration quicConfiguration;
    private final ClientConnectionFactory connectionFactory;
    private final Map<String, Object> context;
    private Scheduler.Task connectTask;
    private ClientQuicheSession session;

    public ClientQuicheConnection(ClientConnector connector, SslContextFactory.Client sslContextFactory, QuicheClientQuicConfiguration quicConfiguration, ClientConnectionFactory connectionFactory, EndPoint endPoint, Map<String, Object> context)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.connectionFactory = connectionFactory;
        this.context = context;
        quicConfiguration.getEventListeners().forEach(this::addEventListener);
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return connectionFactory;
    }

    @Override
    public void onOpen()
    {
        try
        {
            super.onOpen();

            @SuppressWarnings("unchecked")
            List<String> protocols = (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
            if (protocols == null || protocols.isEmpty())
                throw new IllegalStateException("missing ALPN protocols");

            quicConfiguration.configure(sslContextFactory);

            QuicheConfig quicheConfig = new QuicheConfig();
            quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
            quicheConfig.setDisableActiveMigration(quicConfiguration.isDisableActiveMigration());
            quicheConfig.setVerifyPeer(!sslContextFactory.isTrustAll());
            PemPaths pemPaths = (PemPaths)quicConfiguration.getImplementationConfiguration().get(sslContextFactory);
            Path privateKeyPemPath = pemPaths.privateKeyPemPath();
            if (privateKeyPemPath != null)
                quicheConfig.setPrivKeyPemPath(privateKeyPemPath.toString());
            Path certificateChainPemPath = pemPaths.certificateChainPemPath();
            if (certificateChainPemPath != null)
                quicheConfig.setCertChainPemPath(certificateChainPemPath.toString());
            Path trustedCertificatesPemPath = pemPaths.trustedCertificatesPemPath();
            if (trustedCertificatesPemPath != null)
                quicheConfig.setTrustedCertsPemPath(trustedCertificatesPemPath.toString());
            // Idle timeouts must not be managed by Quiche.
            quicheConfig.setMaxIdleTimeout(0L);
            quicheConfig.setInitialMaxData(quicConfiguration.getSessionMaxData());
            quicheConfig.setInitialMaxStreamDataBidiLocal(quicConfiguration.getLocalBidirectionalStreamMaxData());
            quicheConfig.setInitialMaxStreamDataBidiRemote(quicConfiguration.getRemoteBidirectionalStreamMaxData());
            quicheConfig.setInitialMaxStreamDataUni(quicConfiguration.getUnidirectionalStreamMaxData());
            quicheConfig.setInitialMaxStreamsUni(quicConfiguration.getUnidirectionalMaxStreams());
            quicheConfig.setInitialMaxStreamsBidi(quicConfiguration.getBidirectionalMaxStreams());
            quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);

            SocketAddress localAddress = getEndPoint().getLocalSocketAddress();
            InetSocketAddress inetLocalAddress = Quiche.toInetSocketAddress(localAddress, true);
            SocketAddress remoteAddress = (SocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
            InetSocketAddress inetRemoteAddress = Quiche.toInetSocketAddress(remoteAddress, false);

            if (LOG.isDebugEnabled())
                LOG.debug("connecting to {} with protocols {}", remoteAddress, protocols);

            Quiche quiche = Quiche.connect(quicheConfig, inetLocalAddress, inetRemoteAddress);
            Session.Listener listener = (Session.Listener)context.get(Session.Listener.class.getName());
            session = new ClientQuicheSession(connector, quicConfiguration, quiche, this, inetLocalAddress, inetRemoteAddress, listener);
            session.setIdleTimeout(getEndPoint().getIdleTimeout());
            // TODO: would be best if we can attach the session to
            //  an existing ContainerLifeCycle for dumping purposes.
            session.start();
            if (LOG.isDebugEnabled())
                LOG.debug("created {}", session);

            connectTask = getScheduler().schedule(() -> connectTimeout(remoteAddress), connector.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);

            // Send the packets generated by Quiche.connect().
            session.flush();

            fillInterested();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not open {}", this);
            fail(ErrorCode.INTERNAL_ERROR.code(), "open_failure", x);
        }
    }

    @Override
    public void onClose(Throwable cause)
    {
        super.onClose(cause);
        quicConfiguration.deconfigure(sslContextFactory);
    }

    @Override
    public void onFillable()
    {
        connectTask.cancel();

        RetainableByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), quicConfiguration.isUseInputDirectByteBuffers());
        ByteBuffer cipherBuffer = buffer.getByteBuffer();
        try
        {
            while (true)
            {
                BufferUtil.clear(cipherBuffer);
                SocketAddress remoteAddress = getEndPoint().receive(cipherBuffer);
                int filled = remoteAddress == EndPoint.EOF ? -1 : cipherBuffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled cipher buffer with {} byte(s)", filled);
                if (filled < 0)
                {
                    buffer.release();
                    getEndPoint().shutdownOutput();
                    return;
                }
                if (filled == 0)
                {
                    buffer.release();
                    fillInterested();
                    return;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("peer ip address: {}, ciphertext packet size: {}", remoteAddress, cipherBuffer.remaining());

                QuicheConnectionId connectionId = QuicheConnectionId.fromPacket(cipherBuffer);
                if (connectionId == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("dropping packet contains undecipherable connection id");
                    continue;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("packet contains connection id {}", connectionId);

                session.setConnectionId(connectionId);
                InetSocketAddress inetRemoteAddress = Quiche.toInetSocketAddress(remoteAddress, false);
                session.feed(inetRemoteAddress, cipherBuffer);

                if (session.isConnectionEstablished())
                {
                    if (!session.isOpen())
                    {
                        session.open();
                        if (LOG.isDebugEnabled())
                            LOG.debug("opened {}", session);
                    }
                    session.produce();
                }
                else
                {
                    session.flush();
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("receive failure on {}", this, x);
            buffer.release();
            fail(ErrorCode.INTERNAL_ERROR.code(), "receive_failure", x);
        }
    }

    private void connectTimeout(SocketAddress remoteAddress)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("connect timeout {} ms to {} on {}", connector.getConnectTimeout(), remoteAddress, this);
        fail(ErrorCode.CONNECTION_REFUSED_ERROR.code(), "connect_timeout", new SocketTimeoutException("connect timeout"));
    }

    @Override
    public void write(Callback callback, SocketAddress remoteAddress, ByteBuffer... buffers)
    {
        getEndPoint().write(callback, remoteAddress, buffers);
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        boolean idle = isFillInterested();
        long idleTimeout = getEndPoint().getIdleTimeout();
        if (LOG.isDebugEnabled())
            LOG.debug("{} elapsed idle timeout {} ms", idle ? "processing" : "ignoring", idleTimeout);
        if (idle)
            return session.onIdleTimeout(timeoutException);
        return false;
    }

    @Override
    public void close()
    {
        // This method has blocking semantic.
        try (Blocker.Promise<Session> promise = Blocker.promise())
        {
            close(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "close"), promise);
            promise.block();
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    private void close(ConnectionCloseFrame frame, Promise.Invocable<Session> promise)
    {
        if (!closed.compareAndSet(false, true))
        {
            promise.succeeded(session);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("closing {}", this);

        // Propagate the close upwards.
        session.close(frame, promise);
    }

    @Override
    public void disconnect(QuicheSession session, ConnectionCloseFrame frame, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting {}", this);
        this.session = null;
        getEndPoint().close(failure);
    }

    private void fail(long error, String reason, Throwable failure)
    {
        disconnect(session, new ConnectionCloseFrame(error, reason), failure);
        Promise<?> promise = (Promise<?>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
        if (promise != null)
            promise.failed(failure);
    }
}
