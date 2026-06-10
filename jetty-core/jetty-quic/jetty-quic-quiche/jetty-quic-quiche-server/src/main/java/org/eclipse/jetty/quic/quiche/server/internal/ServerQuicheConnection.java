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

package org.eclipse.jetty.quic.quiche.server.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.CyclicTimeouts;
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
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The server specific implementation of {@link QuicheConnection}.</p>
 * <p>Note that there typically is just one instance of this class,
 * because there is only one listening DatagramChannel.</p>
 * <p>This class manages a map of {@link ServerQuicheSession}s,
 * one for each connection id sent by clients.</p>
 * <p>In order to process multiple sessions concurrently,
 * this class uses an {@link AdaptiveExecutionStrategy} so that
 * each active session is processed by a dedicated thread.</p>
 */
public class ServerQuicheConnection extends QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicheConnection.class);

    private final ConcurrentMap<QuicheConnectionId, ServerQuicheSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Flusher flusher = new Flusher();
    private final Connector connector;
    private final SslContextFactory.Server sslContextFactory;
    private final QuicheServerQuicConfiguration quicConfiguration;
    private final Session.Listener.Factory sessionListenerFactory;
    private final SessionTimeouts sessionTimeouts;
    private final InetSocketAddress inetLocalAddress;
    private final AdaptiveExecutionStrategy strategy;

    public ServerQuicheConnection(Connector connector, SslContextFactory.Server sslContextFactory, QuicheServerQuicConfiguration quicConfiguration, EndPoint endPoint, Session.Listener.Factory sessionListenerFactory)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.sessionListenerFactory = sessionListenerFactory;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
        this.inetLocalAddress = Quiche.toInetSocketAddress(endPoint.getLocalSocketAddress(), false);
        this.strategy = new AdaptiveExecutionStrategy(this::produce, getExecutor());
    }

    public Connector getConnector()
    {
        return connector;
    }

    public QuicheServerQuicConfiguration getServerQuicConfiguration()
    {
        return quicConfiguration;
    }

    public Session.Listener.Factory getSessionListenerFactory()
    {
        return sessionListenerFactory;
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        LifeCycle.start(strategy);
        fillInterested();
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

    private Runnable produce()
    {
        boolean interested = isFillInterested();
        if (LOG.isDebugEnabled())
            LOG.debug("produce() fillInterested={}", interested);
        if (interested)
            return null;

        RetainableByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), quicConfiguration.isUseInputDirectByteBuffers());
        ByteBuffer cipherBuffer = buffer.getByteBuffer();
        try
        {
            while (true)
            {
                BufferUtil.clear(cipherBuffer);
                SocketAddress remoteAddress = getEndPoint().receive(cipherBuffer);
                int fill = remoteAddress == EndPoint.EOF ? -1 : cipherBuffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled cipher buffer with {} byte(s)", fill);
                if (fill < 0)
                {
                    buffer.release();
                    getEndPoint().shutdownOutput();
                    return null;
                }
                if (fill == 0)
                {
                    buffer.release();
                    fillInterested();
                    return null;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("peer ip address: {}, ciphertext packet size: {}", remoteAddress, cipherBuffer.remaining());

                QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(cipherBuffer);
                if (quicheConnectionId == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet contains undecipherable connection id, dropping it");
                    continue;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("packet contains connection id {}", quicheConnectionId);

                InetSocketAddress inetRemoteAddress = Quiche.toInetSocketAddress(remoteAddress, true);
                Runnable task = process(quicheConnectionId, inetRemoteAddress, cipherBuffer);
                if (task == null)
                    continue;

                buffer.release();
                return task;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("produce() failure", x);
            buffer.release();
            fail(x);
            return null;
        }
    }

    private Runnable process(QuicheConnectionId connectionId, SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        ServerQuicheSession session = sessions.get(connectionId);
        if (session == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("packet for new session for connection id {}", connectionId);
            session = createSession(remoteAddress, cipherBuffer);
            if (session != null)
            {
                session.setConnectionId(connectionId);
                session.setIdleTimeout(getEndPoint().getIdleTimeout());
                LifeCycle.start(session);
                sessions.put(connectionId, session);
                if (LOG.isDebugEnabled())
                    LOG.debug("session created for connection id {}: {}", connectionId, session);
                return null;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("session not created for connection id {}", connectionId);
            }
            return null;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("packet for session, processing {} bytes on {}", cipherBuffer.remaining(), session);

        return session.process(remoteAddress, cipherBuffer);
    }

    private ServerQuicheSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        InetSocketAddress inetRemoteAddress = (InetSocketAddress)remoteAddress;
        // TODO make the token validator configurable
        Quiche quiche = Quiche.tryAccept(newQuicheConfig(), new SimpleTokenValidator(inetRemoteAddress), cipherBuffer, inetLocalAddress, inetRemoteAddress);
        if (quiche == null)
        {
            RetainableByteBuffer negotiationBuffer = getByteBufferPool().acquire(quicConfiguration.getOutputBufferSize(), quicConfiguration.isUseOutputDirectByteBuffers());
            ByteBuffer byteBuffer = negotiationBuffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);

            // TODO make the token minter configurable
            if (!Quiche.negotiate(new SimpleTokenMinter(inetRemoteAddress), cipherBuffer, byteBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("connection negotiation failed, dropping packet");
                negotiationBuffer.release();
                return null;
            }

            BufferUtil.flipToFlush(byteBuffer, 0);
            write(Callback.from(negotiationBuffer::release), remoteAddress, byteBuffer);

            if (LOG.isDebugEnabled())
                LOG.debug("connection negotiation packet sent");

            return null;
        }
        else
        {
            ServerQuicheSession session = newServerQuicheSession(quiche, remoteAddress);
            // Send the response packet(s) that Quiche.tryAccept() generated.
            session.flush();
            return session;
        }
    }

    public void write(Callback callback, SocketAddress remoteAddress, ByteBuffer... buffers)
    {
        flusher.offer(callback, remoteAddress, buffers);
        flusher.iterate();
    }

    protected ServerQuicheSession newServerQuicheSession(Quiche quiche, SocketAddress remoteAddress)
    {
        Session.Listener listener = sessionListenerFactory.newListener();
        SocketAddress inetLocalSocketAddress = Quiche.toInetSocketAddress(getEndPoint().getLocalSocketAddress(), false);
        return new ServerQuicheSession(connector, quicConfiguration, quiche, this, inetLocalSocketAddress, remoteAddress, listener);
    }

    private QuicheConfig newQuicheConfig()
    {
        QuicheConfig quicheConfig = new QuicheConfig();
        PemPaths pemPaths = (PemPaths)quicConfiguration.getImplementationConfiguration().get(sslContextFactory);
        quicheConfig.setPrivKeyPemPath(pemPaths.privateKeyPemPath().toString());
        quicheConfig.setCertChainPemPath(pemPaths.certificateChainPemPath().toString());
        Path trustedCertificatesPemPath = pemPaths.trustedCertificatesPemPath();
        if (trustedCertificatesPemPath != null)
            quicheConfig.setTrustedCertsPemPath(trustedCertificatesPemPath.toString());
        quicheConfig.setVerifyPeer(sslContextFactory.getNeedClientAuth() || sslContextFactory.getWantClientAuth());
        // Idle timeouts must not be managed by Quiche.
        quicheConfig.setMaxIdleTimeout(0L);
        quicheConfig.setInitialMaxData(quicConfiguration.getSessionMaxData());
        quicheConfig.setInitialMaxStreamDataBidiLocal(quicConfiguration.getBidirectionalLocalStreamMaxData());
        quicheConfig.setInitialMaxStreamDataBidiRemote(quicConfiguration.getBidirectionalRemoteStreamMaxData());
        quicheConfig.setInitialMaxStreamDataUni(quicConfiguration.getUnidirectionalStreamMaxData());
        quicheConfig.setInitialMaxStreamsUni(quicConfiguration.getUnidirectionalMaxStreams());
        quicheConfig.setInitialMaxStreamsBidi(quicConfiguration.getBidirectionalMaxStreams());
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);
        List<String> protocols = connector.getProtocols();
        // This is only needed for Quiche example clients.
        protocols.add(0, "http/0.9");
        quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
        return quicheConfig;
    }

    public void schedule(ServerQuicheSession session)
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
    public void close()
    {
        // This method has blocking semantic.
        try (Blocker.Promise<Void> promise = Blocker.promise())
        {
            close(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "close"), promise);
            promise.block();
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    private void close(ConnectionCloseFrame frame, Promise.Invocable<Void> promise)
    {
        if (!closed.compareAndSet(false, true))
        {
            promise.succeeded(null);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("closing connection {}", this);

        List<CompletableFuture<Session>> closes = new ArrayList<>();
        for (ServerQuicheSession session : sessions.values())
        {
            CompletableFuture<Session> completable = new CompletableFuture<>();
            session.close(frame, Promise.Invocable.toPromise(completable));
            closes.add(completable);
        }
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
            .whenComplete(Promise.Invocable.toBiConsumer(promise));
    }

    @Override
    public void disconnect(QuicheSession session, ConnectionCloseFrame frame, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect {} {} on {}", frame, session, this);
        QuicheConnectionId connectionId = session.getConnectionId();
        if (connectionId != null)
            sessions.remove(connectionId);
        // Do nothing else, as the current architecture only has one
        // listening DatagramChannelEndPoint, so it must not be closed.
    }

    private void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failing connection {}", this, failure);
        ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), "failure");
        for (ServerQuicheSession session : sessions.values())
        {
            session.disconnect(frame, failure, Promise.Invocable.noop());
        }
    }

    private class SessionTimeouts extends CyclicTimeouts<ServerQuicheSession>
    {
        private SessionTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<ServerQuicheSession> iterator()
        {
            return sessions.values().iterator();
        }

        @Override
        protected boolean onExpired(ServerQuicheSession session)
        {
            session.onIdleTimeout(new TimeoutException("Idle timeout " + session.getIdleTimeout() + " ms elapsed"));
            // The implementation of the Iterator returned above does not support
            // removal, but the session will be removed by session.onIdleTimeout().
            return false;
        }
    }

    private class Flusher extends IteratingCallback
    {
        private final AutoLock lock = new AutoLock();
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private Entry entry;

        private void offer(Callback callback, SocketAddress address, ByteBuffer[] buffers)
        {
            try (AutoLock ignored = lock.lock())
            {
                queue.offer(new Entry(callback, address, buffers));
            }
        }

        @Override
        protected Action process()
        {
            try (AutoLock ignored = lock.lock())
            {
                entry = queue.poll();
            }
            if (entry == null)
                return Action.IDLE;

            getEndPoint().write(this, entry.address, entry.buffers);
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            entry.callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable failure)
        {
            entry.callback.failed(failure);
            fail(failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            if (entry == null)
                return InvocationType.NON_BLOCKING;
            return entry.callback.getInvocationType();
        }

        private record Entry(Callback callback, SocketAddress address, ByteBuffer[] buffers)
        {
        }
    }
}
