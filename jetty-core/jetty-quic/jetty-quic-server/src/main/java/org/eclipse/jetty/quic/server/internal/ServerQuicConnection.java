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

package org.eclipse.jetty.quic.server.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.ConnectionId;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSEngine;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.util.Blocker;
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

/// The server-specific implementation of [QuicConnection].
///
/// Note that there typically is just one instance of this class
/// because there is only one listening `DatagramChannel`.
///
/// This class manages a map of [ServerQuicSession]s,
/// one for each connection id sent by clients.
///
/// To process multiple sessions concurrently,
/// this class uses an [AdaptiveExecutionStrategy] so that
/// a dedicated task processes each active session.
public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final ConcurrentMap<ConnectionId, ServerQuicSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Flusher flusher = new Flusher();
    private final Connector connector;
    private final SslContextFactory.Server sslContextFactory;
    private final QuicServerQuicConfiguration quicConfiguration;
    private final Session.Listener.Factory sessionListenerFactory;
    private final SessionTimeouts sessionTimeouts;
    private final AdaptiveExecutionStrategy strategy;
    private int destinationConnectionIdLength;

    public ServerQuicConnection(Connector connector, SslContextFactory.Server sslContextFactory, QuicServerQuicConfiguration quicConfiguration, EndPoint endPoint, Session.Listener.Factory sessionListenerFactory)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.sessionListenerFactory = sessionListenerFactory;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
        this.strategy = new AdaptiveExecutionStrategy(this::produce, getExecutor());
    }

    public Connector getConnector()
    {
        return connector;
    }

    public QuicServerQuicConfiguration getServerQuicConfiguration()
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

    public void schedule(ServerQuicSession session)
    {
        sessionTimeouts.schedule(session);
    }

    @Override
    public void onFillable()
    {
        strategy.produce();
    }

    public int getDestinationConnectionIdLength()
    {
        return destinationConnectionIdLength;
    }

    public void setDestinationConnectionIdLength(int destinationConnectionIdLength)
    {
        this.destinationConnectionIdLength = destinationConnectionIdLength;
    }

    private Runnable produce()
    {
        boolean interested = isFillInterested();
        if (LOG.isDebugEnabled())
            LOG.debug("produce() fillInterested={}", interested);
        if (interested)
            return null;

        RetainableByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), quicConfiguration.isUseInputDirectByteBuffers());
        try
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            while (true)
            {
                SocketAddress address = getEndPoint().receive(byteBuffer);
                int filled = address == EndPoint.EOF ? -1 : buffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} bytes from {} on {}", filled, address, getEndPoint());

                if (filled < 0)
                {
                    buffer.release();
                    getEndPoint().shutdownOutput();
                    return null;
                }
                if (filled == 0)
                {
                    buffer.release();
                    fillInterested();
                    return null;
                }

                // Retrieve the destination connection id from the packet.
                int position = byteBuffer.position();
                byte[] bytes;
                if (Packet.isLongHeader(byteBuffer.get(position)))
                {
                    // Skip form and version bytes.
                    int offset = position + 1 + 4;
                    bytes = new byte[byteBuffer.get(offset) & 0xFF];
                    byteBuffer.get(offset + 1, bytes);
                }
                else
                {
                    // Skip the form byte.
                    int offset = position + 1;
                    bytes = new byte[getDestinationConnectionIdLength()];
                    byteBuffer.get(offset, bytes);
                }
                ConnectionId dstConnectionId = new ConnectionId(bytes);

                if (LOG.isDebugEnabled())
                    LOG.debug("packet dcid {} on {}", dstConnectionId, this);

                Runnable task = process(dstConnectionId, address, buffer);
                if (task == null)
                    continue;

                buffer.release();
                return task;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("produce() failure");
            buffer.release();
            fail(x);
            return null;
        }
    }

    private Runnable process(ConnectionId dstConnectionId, SocketAddress remoteAddress, RetainableByteBuffer buffer) throws Exception
    {
        ServerQuicSession session = sessions.get(dstConnectionId);
        if (session == null)
        {
            // Create the session.
            session = newSession();
            // Configure the session.
            session.initialize(dstConnectionId.bytes());
            session.setRemoteSocketAddress(remoteAddress);
            long idleTimeout = getEndPoint().getIdleTimeout();
            session.setIdleTimeout(idleTimeout);
            // Start and store the session.
            LifeCycle.start(session);
            sessions.put(new ConnectionId(session.getSourceConnectionId()), session);

            ServerTLSConfiguration tlsConfiguration = session.getTLSEngine().getTLSConfiguration();
            tlsConfiguration.setApplicationProtocols(connector.getProtocols());

            // RFC-9000[18.2].
            TransportParameters transportParameters = tlsConfiguration.getTransportParameters();
            transportParameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, Math.max(idleTimeout, 0L));
            transportParameters.put(TransportParameters.Ids.ORIGINAL_DESTINATION_CONNECTION_ID, dstConnectionId.bytes());
            // TODO
//            transportParameters.put(TransportParameters.Ids.PREFERRED_ADDRESS, null);
            transportParameters.put(TransportParameters.Ids.INITIAL_SOURCE_CONNECTION_ID, session.getSourceConnectionId());

            session.notifyPrepare(transportParameters);

            if (LOG.isDebugEnabled())
                LOG.debug("created new {} on {}", session, this);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("processing {} for {} on {}", buffer, session, this);

        // TODO: this is where we want to parallelize session processing
        //  by returning a task that can be run by the ExecutionStrategy.
        //  However, do we want to do that? It would add complexity to
        //  buffer reuse, and perhaps not worth it, as we parallelize
        //  later by stream.
        //  buffer.retain();
        //  return () -> session.process(buffer); // What InvocationType?
        session.process(remoteAddress, buffer);
        return null;
    }

    private ServerQuicSession newSession()
    {
        PacketNumbers packetNumbers = new PacketNumbers();
        ByteBufferPool byteBufferPool = getByteBufferPool();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, true), new QuicMessagesGenerator(byteBufferPool, false));
        PacketProtector protector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, false);
        ServerTLSConfiguration tlsConfiguration = new ServerTLSConfiguration(getServerQuicConfiguration(), getSslContextFactory());
        ServerTLSEngine tlsEngine = new ServerTLSEngine(protector, tlsConfiguration);
        Session.Listener listener = getSessionListenerFactory().newListener();
        return new ServerQuicSession(connector, getServerQuicConfiguration(), this, packetNumbers, tlsEngine, listener, getEndPoint());
    }

    public void write(Callback callback, SocketAddress remoteAddress, ByteBuffer... buffers)
    {
        flusher.offer(callback, remoteAddress, buffers);
        flusher.iterate();
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
        for (ServerQuicSession session : sessions.values())
        {
            CompletableFuture<Session> completable = new CompletableFuture<>();
            session.close(frame, Promise.Invocable.toPromise(completable));
            closes.add(completable);
        }
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
            .whenComplete(Promise.Invocable.toBiConsumer(promise));
    }

    @Override
    public void disconnect(QuicSession session, ConnectionCloseFrame frame, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect {} {} on {}", frame, session, this);
        byte[] dstConnectionId = session.getDestinationConnectionId();
        sessions.remove(new ConnectionId(dstConnectionId));
        // Do nothing else, as the current architecture only has one
        // listening DatagramChannelEndPoint, so it must not be closed.
    }

    private void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(failure).log("failing connection {}", this);
        ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), "failure");
        for (ServerQuicSession session : sessions.values())
        {
            session.disconnect(frame, failure, Promise.Invocable.noop());
        }
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
            return sessions.values().iterator();
        }

        @Override
        protected boolean onExpired(ServerQuicSession session)
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
