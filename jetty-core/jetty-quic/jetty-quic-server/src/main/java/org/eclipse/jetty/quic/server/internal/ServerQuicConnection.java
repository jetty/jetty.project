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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.FlowController;
import org.eclipse.jetty.quic.common.PacketTracker;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.StreamsController;
import org.eclipse.jetty.quic.common.packets.ConnectionId;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.packets.VersionNegotiationPacket;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSEngine;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable.Task;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The server-specific implementation of [QuicConnection].
///
/// Note that there typically is just one instance of this class
/// because there is only one listening `DatagramChannel`.
///
/// This class manages a map of [ServerQuicSession]s,
/// one for each connection id sent by clients.
public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final AtomicLong bytesIn = new AtomicLong();
    private final ConcurrentMap<ConnectionId, ServerQuicSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Connector connector;
    private final SslContextFactory.Server sslContextFactory;
    private final QuicServerQuicConfiguration quicConfiguration;
    private final Session.Listener.Factory sessionListenerFactory;
    private final SessionTimeouts sessionTimeouts;
    private int destinationConnectionIdLength;

    public ServerQuicConnection(Connector connector, SslContextFactory.Server sslContextFactory, QuicServerQuicConfiguration quicConfiguration, EndPoint endPoint, Session.Listener.Factory sessionListenerFactory)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.sessionListenerFactory = sessionListenerFactory;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
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

    public Collection<Session> getSessions()
    {
        return List.copyOf(sessions.values());
    }

    public void schedule(ServerQuicSession session)
    {
        sessionTimeouts.schedule(session);
    }

    public int getDestinationConnectionIdLength()
    {
        return destinationConnectionIdLength;
    }

    public void setDestinationConnectionIdLength(int destinationConnectionIdLength)
    {
        this.destinationConnectionIdLength = destinationConnectionIdLength;
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.get();
    }

    @Override
    protected Runnable doProduce()
    {
        RetainableByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), quicConfiguration.isUseInputDirectByteBuffers());
        try
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            while (true)
            {
                SocketAddress address = getEndPoint().receive(byteBuffer);
                int filled = address == EndPoint.EOF ? -1 : buffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} bytes from {} on {}", filled, address, this);

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

                bytesIn.addAndGet(filled);

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

                process(dstConnectionId, address, buffer);

                Task task = pollTask();
                if (LOG.isDebugEnabled())
                    LOG.debug("produced task {} on {}", task, this);
                if (task == null)
                    continue;

                buffer.release();
                return task;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed to produce on {}", this, x);
            buffer.release();
            fail(x);
            return null;
        }
    }

    private void process(ConnectionId dstConnectionId, SocketAddress remoteAddress, RetainableByteBuffer buffer) throws Exception
    {
        ServerQuicSession session = sessions.get(dstConnectionId);
        if (session == null)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int position = byteBuffer.position();

            if (!Packet.isLongHeader(byteBuffer.get(position)))
            {
                // No session, cannot decrypt (no keys), just drop it.
                if (LOG.isDebugEnabled())
                    LOG.debug("dropping datagram {} on {}", BufferUtil.toDetailString(byteBuffer), this);
                BufferUtil.clear(byteBuffer);
                return;
            }

            // Create the session.
            session = newSession();

            // RFC-9000[17.2.1]: version negotiation.
            int quicVersionCode = byteBuffer.getInt(position + 1);
            QuicVersion quicVersion = QuicVersion.from(quicVersionCode);
            List<QuicVersion> quicVersions = getServerQuicConfiguration().getQuicVersions();
            if (quicVersion == null || !quicVersions.contains(quicVersion))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unsupported quic version 0x{} on {}", Integer.toHexString(quicVersionCode), this);
                // RFC-9000[17.2.1]: echo scid and dcid.
                byte[] dcid = dstConnectionId.bytes();
                int offset = position + 1 + 4 + 1 + dcid.length;
                int scidLength = byteBuffer.get(offset) & 0xFF;
                byte[] scid = new byte[scidLength];
                byteBuffer.get(offset + 1, scid);
                VersionNegotiationPacket versionNegotiationPacket = new VersionNegotiationPacket(scid, dcid, quicVersions);
                // Use the un-initialized session to send the version
                // negotiation packet and then throw the session away.
                session.packet(versionNegotiationPacket, Callback.from(session::dispose));
                BufferUtil.clear(byteBuffer);
                return;
            }

            // Configure the session.
            session.setQuicVersion(quicVersion);
            session.setRemoteSocketAddress(remoteAddress);
            long idleTimeout = getEndPoint().getIdleTimeout();
            session.setIdleTimeout(idleTimeout);
            // TODO: initialize() may throw, must dispose the session.
            session.initialize(dstConnectionId.bytes());
            // Start and store the session.
            LifeCycle.start(session);
            // Store the session under both the client original dcid and the server scid.
            // The client may use both, for example in case of retransmissions.
            sessions.put(dstConnectionId, session);
            sessions.put(new ConnectionId(session.getSourceConnectionId()), session);

            TransportParameters parameters = new TransportParameters();
            quicConfiguration.configure(parameters);

            ServerTLSConfiguration tlsConfiguration = session.getTLSEngine().getTLSConfiguration();
            tlsConfiguration.setTransportParameters(parameters);
            tlsConfiguration.setApplicationProtocols(connector.getProtocols());

            // RFC-9000[18.2].
            if (idleTimeout > 0)
                parameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
            parameters.put(TransportParameters.Ids.ORIGINAL_DESTINATION_CONNECTION_ID, dstConnectionId.bytes());
            // TODO
//            parameters.put(TransportParameters.Ids.PREFERRED_ADDRESS, null);

            if (LOG.isDebugEnabled())
                LOG.debug("created new {} on {}", session, this);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("processing {} for {} on {}", buffer, session, this);

        session.process(remoteAddress, buffer);
    }

    private ServerQuicSession newSession()
    {
        CongestionController congestionController = quicConfiguration.getCongestionControllerFactory().newCongestionController();
        PacketTracker packetTracker = new PacketTracker(getScheduler(), congestionController);
        packetTracker.setAcknowledgmentMaxDelay(quicConfiguration.getAcknowledgmentMaxDelay());
        packetTracker.setAcknowledgmentDelayExponent(quicConfiguration.getAcknowledgmentDelayExponent());
        PacketNumbers packetNumbers = new PacketNumbers();
        ByteBufferPool byteBufferPool = getByteBufferPool();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, true), new QuicMessagesGenerator(byteBufferPool, false));
        PacketProtector protector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, false);
        ServerTLSConfiguration tlsConfiguration = new ServerTLSConfiguration(getServerQuicConfiguration(), getSslContextFactory());
        ServerTLSEngine tlsEngine = new ServerTLSEngine(protector, tlsConfiguration);
        FlowController flowController = quicConfiguration.getFlowControllerFactory().newFlowController();
        StreamsController streamsController = quicConfiguration.getStreamsControllerFactory().newStreamsController();
        Session.Listener listener = getSessionListenerFactory().newListener();
        return new ServerQuicSession(connector, quicConfiguration, this, packetTracker, packetNumbers, tlsEngine, flowController, streamsController, listener);
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
            close(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "close", 0x00), promise);
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
            LOG.debug("closing {}", this);

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
    protected void terminate(QuicSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminate {} on {}", session, this);

        byte[] srcConnectionId = session.getSourceConnectionId();
        sessions.remove(new ConnectionId(srcConnectionId));
        byte[] origDstConnectionId = session.getOriginalDestinationConnectionId();
        sessions.remove(new ConnectionId(origDstConnectionId));

        // Do nothing else, as the current architecture only has one
        // listening DatagramChannelEndPoint, so it must not be closed.
    }

    private void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failing connection {}", this, failure);
        ConnectionCloseFrame frame = new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), "failure", 0x00);
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
}
