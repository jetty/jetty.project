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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSEngine;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.Tokens;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.ServerNameExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientQuicSession extends QuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicSession.class);

    private final Tokens tokens = new Tokens();
    private final Map<String, Object> context;
    private byte[] firstDstConnectionId;

    public ClientQuicSession(ClientConnector connector, QuicClientQuicConfiguration quicConfiguration, ClientQuicConnection connection, PacketNumbers packetNumbers, ClientTLSEngine clientTLSEngine, EndPoint endPoint, Map<String, Object> context)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), quicConfiguration, connection, packetNumbers, clientTLSEngine, sessionListener(context), endPoint);
        this.context = context;
        this.firstDstConnectionId = BufferUtil.EMPTY_BYTES;
    }

    private static Session.Listener sessionListener(Map<String, Object> context)
    {
        return (Session.Listener)context.get(QuicClient.SESSION_LISTENER_CONTEXT_KEY);
    }

    @SuppressWarnings("unchecked")
    private static List<String> alpnProtocols(Map<String, Object> context)
    {
        return (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
    }

    @Override
    public ClientTLSEngine getTLSEngine()
    {
        return (ClientTLSEngine)super.getTLSEngine();
    }

    @Override
    public byte[] getDestinationConnectionId()
    {
        byte[] dstConnectionId = super.getDestinationConnectionId();
        return dstConnectionId.length == 0 ? firstDstConnectionId : dstConnectionId;
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
        SocketAddress remoteSocketAddress = (SocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
        setRemoteSocketAddress(remoteSocketAddress);
        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {} on {}", remoteSocketAddress, this);

        ClientTLSEngine.Configuration configuration = new ClientTLSEngine.Configuration()
            .quicVersion(getQuicConfiguration().getQuicVersion());

        if (remoteSocketAddress instanceof InetSocketAddress inet)
        {
            String serverName = inet.getHostString();
            configuration.extension(new ServerNameExtension(serverName));
        }

        List<String> protocols = alpnProtocols(context);
        if (protocols == null || protocols.isEmpty())
            throw new IllegalStateException("missing ALPN protocols");
        configuration.extension(new ALPNExtension(protocols));

        TransportParameters transportParameters = getListener().onPrepare(this);
        if (transportParameters == null)
            transportParameters = new TransportParameters();
        getQuicConfiguration().configure(transportParameters);
        transportParameters.put(TransportParameters.Ids.INITIAL_SOURCE_CONNECTION_ID, getSourceConnectionId());
        transportParameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, getIdleTimeout());
        transportParameters.put(TransportParameters.Ids.ACTIVE_CONNECTION_ID_LIMIT, 2L);

        configuration.extension(new QuicTransportParametersExtension(transportParameters));

        byte[] dstConnectionId = getTLSEngine().newRandomBytes(12);
        configuration.inputKeyMaterial(dstConnectionId);
        firstDstConnectionId = dstConnectionId;

        // Link the ClientTLSEngine back to this session to
        // send the TLS messages generated by ClientTLSEngine.
        getTLSEngine().addMessageListener(this::sendTLSMessages);

        // Link the TLS message generator back to ClientTLSEngine, so that
        // it can update the TranscriptHash as TLS messages are generated.
        getTLSEngine().getMessagesGenerator().addListener(getTLSEngine());

        // TODO: link the ClientTLSEngine to receive the TLS messages.

        CompletableFuture<?> handshake = getTLSEngine().startHandshake(configuration, callback);
        handshake.whenComplete((_, x) -> handshakeComplete(x));
    }

    private void handshakeComplete(Throwable failure)
    {
        if (failure == null)
            notifyOpen();
        else
            fail(failure);
    }

    private void sendTLSMessages(List<Message> messages, Callback callback)
    {
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(getByteBufferPool(), getQuicConfiguration().isUseOutputDirectByteBuffers(), -1, 0, 0);
        try
        {
            for (Message message : messages)
            {
                getTLSEngine().getMessagesGenerator().generate(accumulator, message);
            }
            // TODO: cannot assume offset is 0 here.
            CryptoFrame cryptoFrame = new CryptoFrame(0, accumulator);
            crypto(cryptoFrame, callback);
        }
        catch (Throwable x)
        {
            accumulator.release();
            callback.failed(x);
        }
    }

    @Override
    protected InitialPacket newInitialPacket(List<Frame> frames)
    {
        byte[] token = tokens.get(getEndPoint().getLocalSocketAddress(), getRemoteSocketAddress());
        return new InitialPacket(getQuicConfiguration().getQuicVersion(), getDestinationConnectionId(), getSourceConnectionId(), token, getPacketNumbers().nextPacketNumber(EncryptionLevel.INITIAL), frames);
    }

    @Override
    protected void processPacket(Packet packet)
    {
        switch (packet)
        {
            case RetryPacket retryPacket -> processRetryPacket(retryPacket);
            default -> super.processPacket(packet);
        }
    }

    private void processRetryPacket(RetryPacket packet)
    {
        tokens.put(getEndPoint().getLocalSocketAddress(), getRemoteSocketAddress(), packet.token());

        // RFC-9000[17.2.5.1]: discard retry packets that
        // have the same dcid as the first initial packet.
        if (Arrays.equals(getDestinationConnectionId(), packet.sourceConnectionId()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalid destination connection id, discarding {} on {}", packet, this);
            return;
        }
        // TODO: this must be done, but apparently Quiche does not like it.
//        setDestinationConnectionId(packet.sourceConnectionId());

        getTLSEngine().retryHandshake();
    }
}
