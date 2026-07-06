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

package org.eclipse.jetty.quic.client.internal;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.HandshakeDoneFrame;
import org.eclipse.jetty.quic.api.frames.NewTokenFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSEngine;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.FlowController;
import org.eclipse.jetty.quic.common.PacketTracker;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.StreamsController;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.quic.common.packets.VersionNegotiationPacket;
import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.quic.common.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientQuicSession extends QuicSession
{
    public static final ByteBuffer NO_EARLY_DATA = ByteBuffer.allocate(0);
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicSession.class);

    private final Map<String, Object> context;
    private SocketAddress serverSocketAddress;
    private Scheduler.Task connectTask;
    private boolean retryPacketProcessed;
    private byte[] retryToken;

    public ClientQuicSession(ClientConnector connector, QuicClientQuicConfiguration quicConfiguration, ClientQuicConnection connection, PacketTracker packetTracker, PacketNumbers packetNumbers, ClientTLSEngine clientTLSEngine, RateControl rateControl, FlowController flowController, StreamsController streamsController, Map<String, Object> context)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), quicConfiguration, connection, packetTracker, packetNumbers, clientTLSEngine, rateControl, flowController, streamsController, sessionListener(context), true);
        this.context = context;
    }

    private static Session.Listener sessionListener(Map<String, Object> context)
    {
        return (Session.Listener)context.get(QuicClient.SESSION_LISTENER_CONTEXT_KEY);
    }

    @SuppressWarnings("unchecked")
    private static Promise<Session> sessionPromise(Map<String, Object> context)
    {
        return (Promise<Session>)context.get(QuicClient.SESSION_PROMISE_CONTEXT_KEY);
    }

    @SuppressWarnings("unchecked")
    private static List<String> alpnProtocols(Map<String, Object> context)
    {
        return (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
    }

    @Override
    public QuicClientQuicConfiguration getQuicConfiguration()
    {
        return (QuicClientQuicConfiguration)super.getQuicConfiguration();
    }

    @Override
    public ClientQuicConnection getQuicConnection()
    {
        return (ClientQuicConnection)super.getQuicConnection();
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return getQuicConnection().getClientConnectionFactory();
    }

    @Override
    public ClientTLSEngine getTLSEngine()
    {
        return (ClientTLSEngine)super.getTLSEngine();
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        getEndPoint().setIdleTimeout(idleTimeout);
    }

    @Override
    protected void notIdle()
    {
        if (getEndPoint() instanceof AbstractEndPoint e)
            e.notIdle();
    }

    /// Establishes a connection to the server, starting the QUIC TLS handshake.
    ///
    /// The QUIC TLS handshake completion is notified to the [Session.Listener].
    ///
    /// The `callback` parameter is notified when the TLS `ClientHello` has been sent,
    /// so that the caller can arrange to start reading from the network to complete
    /// the QUIC TLS handshake.
    ///
    /// @param callback the [Callback] notified when the TLS `ClientHello` has been sent.
    void connect(Callback callback)
    {
        serverSocketAddress = (SocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
        setRemoteSocketAddress(serverSocketAddress);
        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {} on {}", serverSocketAddress, this);

        SslContextFactory.Client sslContextFactory = (SslContextFactory.Client)context.get(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY);

        QuicClient quicClient = (QuicClient)context.get(QuicClient.CONTEXT_KEY);

        ClientTLSConfiguration tlsConfiguration = new ClientTLSConfiguration(getQuicConfiguration(), sslContextFactory, quicClient.getZeroRTTStore());

        QuicVersion quicVersion = getQuicConfiguration().getQuicVersions().getFirst();
        setQuicVersion(quicVersion);
        tlsConfiguration.setQuicVersion(quicVersion);

        if (serverSocketAddress instanceof InetSocketAddress inet)
        {
            String serverName = inet.getHostString();
            tlsConfiguration.setServerName(serverName);
        }

        List<String> protocols = alpnProtocols(context);
        if (protocols != null && !protocols.isEmpty())
            tlsConfiguration.setApplicationProtocols(protocols);

        TransportParameters transportParameters = new TransportParameters();
        getQuicConfiguration().configure(transportParameters);
        tlsConfiguration.setTransportParameters(transportParameters);
        long idleTimeout = getIdleTimeout();
        if (idleTimeout > 0)
            transportParameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
        transportParameters.put(TransportParameters.Ids.INITIAL_SOURCE_CONNECTION_ID, getSourceConnectionId());
        notifyPrepare(transportParameters);
        configure(transportParameters, true);

        byte[] dstConnectionId = getTLSEngine().newRandomBytes(12);
        tlsConfiguration.setInputKeyMaterial(dstConnectionId);
        // Store the original destination connection id,
        // to be used later for RetryPacket processing.
        setOriginalDestinationConnectionId(dstConnectionId);
        setDestinationConnectionId(dstConnectionId);

        ByteBuffer earlyData = (ByteBuffer)context.get(QuicClient.EARLY_DATA_KEY);
        if (earlyData != null && earlyData != NO_EARLY_DATA)
            tlsConfiguration.setEarlyData(RetainableByteBuffer.wrap(earlyData));

        // Link the ClientTLSEngine back to this session to
        // send the TLS messages generated by ClientTLSEngine.
        getTLSEngine().addMessageListener(new TLSEngine.MessageListener()
        {
            @Override
            public void onOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
            {
                sendTLSMessages(encryptionLevel, messages, callback);
            }
        });

        // Link the ClientTLSEngine back to this session to
        // be notified when the TLS handshake is complete.
        getTLSEngine().addHandshakeListener(this::handshakeComplete);

        ClientConnector connector = (ClientConnector)context.get(ClientConnector.CONTEXT_KEY);
        connectTask = getScheduler().schedule(() -> connectTimeout(serverSocketAddress, callback), connector.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);

        getTLSEngine().startHandshake(tlsConfiguration, callback);
    }

    private void connectTimeout(SocketAddress remoteAddress, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("connect timeout to {} on {}", remoteAddress, this);
        callback.failed(new QuicException(ErrorCode.CONNECTION_REFUSED_ERROR, "connect_timeout", 0x06));
    }

    private void handshakeComplete(HandshakeData data, Throwable failure)
    {
        if (failure != null)
            fail(failure);
    }

    @Override
    public int getUDPPayloadLength()
    {
        return getQuicConfiguration().getUDPPayloadLength();
    }

    @Override
    public int estimatePacketHeaderLength(EncryptionLevel encryptionLevel)
    {
        int result = super.estimatePacketHeaderLength(encryptionLevel);
        if (encryptionLevel == EncryptionLevel.INITIAL)
        {
            // TODO
            int tokenLength = 0;
            result += tokenLength;
        }
        return result;
    }

    @Override
    protected InitialPacket newInitialPacket(List<Frame> frames)
    {
        byte[] token = null;

        if (frames.getFirst() instanceof CryptoFrame)
        {
            if (retryToken != null)
            {
                token = retryToken;
                retryToken = null;
            }
            else
            {
                QuicClient quicClient = (QuicClient)context.get(QuicClient.CONTEXT_KEY);
                token = quicClient.getTokenStore().retrieve(getLocalSocketAddress(), getRemoteSocketAddress());
            }
        }

        return new InitialPacket(getQuicVersion(), getDestinationConnectionId(), getSourceConnectionId(), token, getPacketNumbers().nextPacketNumber(EncryptionLevel.INITIAL), frames);
    }

    @Override
    protected List<Invocable.Task> processPacket(Packet packet)
    {
        try
        {
            return switch (packet)
            {
                case RetryPacket retryPacket ->
                {
                    processRetryPacket(retryPacket);
                    yield List.of();
                }
                case VersionNegotiationPacket versionNegotiationPacket ->
                {
                    processVersionNegotiationPacket(versionNegotiationPacket);
                    yield List.of();
                }
                default -> super.processPacket(packet);
            };
        }
        catch (Throwable x)
        {
            fail(x);
            return List.of();
        }
    }

    @Override
    protected void processFrame(Packet.WithFrames packet, Frame frame)
    {
        switch (frame)
        {
            case HandshakeDoneFrame handshakeDone -> processHandshakeDoneFrame(packet, handshakeDone);
            case NewTokenFrame newTokenFrame -> processNewTokenFrame(newTokenFrame);
            default -> super.processFrame(packet, frame);
        }
    }

    private void processHandshakeDoneFrame(Packet.WithFrames packet, HandshakeDoneFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} in {} on {}", frame, packet, this);

        // RFC-9001[4.9.2]: handshake keys must be discarded when the TLS handshake is confirmed.
        discardEncryptionLevel(EncryptionLevel.HANDSHAKE);
        setEncryptionLevel(EncryptionLevel.ONE_RTT);

        emitOpen();

        sessionPromise(context).succeeded(this);
    }

    private void processNewTokenFrame(NewTokenFrame frame)
    {
        QuicClient quicClient = (QuicClient)context.get(QuicClient.CONTEXT_KEY);
        quicClient.getTokenStore().store(getLocalSocketAddress(), serverSocketAddress, frame.token());
    }

    @Override
    protected void processMessage(Message message)
    {
        switch (message)
        {
            case ServerHelloMessage serverHello -> processServerHello(serverHello);
            case EncryptedExtensionsMessage encryptedExtensions -> processEncryptedExtensions(encryptedExtensions);
            case CertificateMessage certificate -> getTLSEngine().onMessage(EncryptionLevel.HANDSHAKE, certificate);
            case CertificateVerifyMessage certificateVerify -> getTLSEngine().onMessage(EncryptionLevel.HANDSHAKE, certificateVerify);
            case FinishedMessage finished -> getTLSEngine().onMessage(EncryptionLevel.HANDSHAKE, finished);
            case NewSessionTicketMessage newSessionTicket -> getTLSEngine().onMessage(EncryptionLevel.ONE_RTT, newSessionTicket);
            default -> throw new IllegalStateException("unexpected message " + message);
        }
    }

    private void processServerHello(ServerHelloMessage serverHello)
    {
        connectTask.cancel();
        getTLSEngine().onMessage(EncryptionLevel.INITIAL, serverHello);
    }

    private void processEncryptedExtensions(EncryptedExtensionsMessage encryptedExtensions)
    {
        TransportParameters transportParameters = encryptedExtensions.extensions().stream()
            .filter(ext -> ext instanceof QuicTransportParametersExtension)
            .map(QuicTransportParametersExtension.class::cast)
            .findFirst()
            .map(QuicTransportParametersExtension::transportParameters)
            .orElse(null);

        processTransportParameters(transportParameters);

        getTLSEngine().onMessage(EncryptionLevel.HANDSHAKE, encryptedExtensions);
    }

    private void processRetryPacket(RetryPacket packet) throws Exception
    {
        // RFC-9000[17.2.5.1]: discard retry packets that
        // have the same dcid as the first initial packet.
        if (!Arrays.equals(getDestinationConnectionId(), packet.sourceConnectionId()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalid destination connection id, discarding {} on {}", packet, this);
            return;
        }

        // RFC-9000[17.2.5.2]: only one retry packet can be processed.
        if (retryPacketProcessed)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("discarding non-first {} on {}", packet, this);
            return;
        }

        // Verify the integrity of the RetryPacket.
        RetainableByteBuffer.Mutable retryAccumulator = new RetainableByteBuffer.DynamicCapacity(getByteBufferPool(), false, -1, 0, 0);
        generateRetryPacket(retryAccumulator, packet);
        boolean verified = getTLSEngine().verifyRetryIntegrity(retryAccumulator, getOriginalDestinationConnectionId());
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} on {}", verified ? "processing verified" : "discarding non-verified", packet, this);
        if (!verified)
        {
            // RFC-9000[17.2.5.2]: discard retry packets that do not verify.
            return;
        }

        retryPacketProcessed = true;
        retryToken = packet.token();

        // RFC-9001[5.2]: initial secrets must be regenerated with the new scid.
        getTLSEngine().getPacketProtector().generateInitialKeys(getQuicVersion(), packet.sourceConnectionId());

        // The RetryPacket implicitly acknowledges the first InitialPacket.
        // TODO: do not hardcode packetNumber=0.
        onAcknowledge(EncryptionLevel.INITIAL, 0);

        resetCrypto();
        getTLSEngine().retryHandshake();
    }

    private void processVersionNegotiationPacket(VersionNegotiationPacket packet)
    {
        // TODO: negotiate version and
    }

    @Override
    public void fail(Throwable x)
    {
        connectTask.cancel();
        super.fail(x);
        sessionPromise(context).failed(x);
    }
}
