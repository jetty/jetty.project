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

package org.eclipse.jetty.quic.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSConfiguration;
import org.eclipse.jetty.quic.client.internal.tls.ClientTLSEngine;
import org.eclipse.jetty.quic.common.DefaultZeroRTTStore;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSEngine;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConstantThrowable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TLSEngineTest
{
    private final Throwable success = new ConstantThrowable();
    private final AtomicReference<Throwable> serverHandshake = new AtomicReference<>();
    private final AtomicReference<Throwable> clientHandshake = new AtomicReference<>();
    private ServerTLSEngine serverEngine;
    private List<Message> serverOutMessages;
    private ClientTLSConfiguration clientTLSConfiguration;
    private ClientTLSEngine clientEngine;
    private List<Message> clientOutMessages;

    @BeforeEach
    public void prepare() throws Exception
    {
        prepareServer();
        prepareClient();
    }

    private void prepareServer() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        PacketNumbers packetNumbers = new PacketNumbers();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, true), new QuicMessagesGenerator(byteBufferPool, false));
        PacketProtector packetProtector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, false);
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("server_keystore.p12"));
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.start();
        QuicServerQuicConfiguration quicConfiguration = new QuicServerQuicConfiguration();
        TransportParameters parameters = new TransportParameters();
        quicConfiguration.configure(parameters);
        ServerTLSConfiguration serverTLSConfiguration = new ServerTLSConfiguration(quicConfiguration, sslContextFactory);
        serverTLSConfiguration.setTransportParameters(parameters);
        // TODO: parametrize on the version.
        serverTLSConfiguration.setQuicVersion(QuicVersion.V1);
        serverEngine = new ServerTLSEngine(packetProtector, serverTLSConfiguration);
        serverEngine.initialize();

        serverOutMessages = new ArrayList<>();
        serverEngine.addMessageListener(new TLSEngine.MessageListener()
        {
            @Override
            public void onOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
            {
                serverOutMessages.addAll(messages);
                callback.succeeded();
            }
        });

        serverEngine.addHandshakeListener((_, failure) -> serverHandshake.set(failure == null ? success : failure));
    }

    private void prepareClient() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        PacketNumbers packetNumbers = new PacketNumbers();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, false), new QuicMessagesGenerator(byteBufferPool, true));
        PacketProtector packetProtector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, true);
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.start();
        QuicClientQuicConfiguration quicConfiguration = new QuicClientQuicConfiguration();
        TransportParameters parameters = new TransportParameters();
        quicConfiguration.configure(parameters);
        clientTLSConfiguration = new ClientTLSConfiguration(quicConfiguration, sslContextFactory, new DefaultZeroRTTStore());
        clientTLSConfiguration.setInputKeyMaterial(new byte[12]);
        clientTLSConfiguration.setServerName("localhost");
        clientTLSConfiguration.setTransportParameters(parameters);
        clientTLSConfiguration.setQuicVersion(QuicVersion.V1);
        clientEngine = new ClientTLSEngine(packetProtector);

        clientOutMessages = new ArrayList<>();
        clientEngine.addMessageListener(new TLSEngine.MessageListener()
        {
            @Override
            public void onOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
            {
                clientOutMessages.addAll(messages);
                callback.succeeded();
            }
        });

        clientEngine.addHandshakeListener((_, failure) -> clientHandshake.set(failure == null ? success : failure));
    }

    @Test
    public void testHandshake()
    {
        clientEngine.startHandshake(clientTLSConfiguration, Callback.NOOP);

        // Client emits only ClientHello.
        assertThat(clientOutMessages.size(), equalTo(1));

        serverEngine.onMessage(EncryptionLevel.INITIAL, clientOutMessages.getFirst());

        clientOutMessages.clear();

        // Server emits ServerHello, EncryptedExtensions, Certificate, CertificateVerify, Finished.
        assertThat(serverOutMessages.size(), equalTo(5));

        for (int i = 0; i < serverOutMessages.size(); ++i)
        {
            Message serverOutMessage = serverOutMessages.get(i);
            EncryptionLevel encryptionLevel = (i == 0) ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            clientEngine.onMessage(encryptionLevel, serverOutMessage);
        }

        serverOutMessages.clear();

        // Client emits Finished.
        assertThat(clientOutMessages.size(), equalTo(1));

        serverEngine.onMessage(EncryptionLevel.HANDSHAKE, clientOutMessages.getFirst());

        clientOutMessages.clear();

        // Server emits NewSessionTicket.
        assertThat(serverOutMessages.size(), equalTo(1));

        clientEngine.onMessage(EncryptionLevel.ONE_RTT, serverOutMessages.getFirst());

        serverOutMessages.clear();

        // No more messages emitted by the client.
        assertThat(clientOutMessages.size(), equalTo(0));

        assertSame(serverHandshake.get(), success);
        assertSame(clientHandshake.get(), success);
    }

    @Test
    public void testWantClientAuthenticationNoCertificate()
    {
        serverEngine.getTLSConfiguration().getSslContextFactory().setWantClientAuth(true);

        clientEngine.startHandshake(clientTLSConfiguration, Callback.NOOP);
        serverEngine.onMessage(EncryptionLevel.INITIAL, clientOutMessages.getFirst());
        clientOutMessages.clear();

        // Server emits ServerHello, EncryptedExtensions, CertificateRequest, Certificate, CertificateVerify, Finished.
        assertThat(serverOutMessages.size(), equalTo(6));
        for (int i = 0; i < serverOutMessages.size(); ++i)
        {
            Message serverOutMessage = serverOutMessages.get(i);
            EncryptionLevel encryptionLevel = (i == 0) ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            clientEngine.onMessage(encryptionLevel, serverOutMessage);
        }
        serverOutMessages.clear();

        // Client emits an empty CertificateMessage and Finished.
        assertThat(clientOutMessages.size(), equalTo(2));
        assertInstanceOf(CertificateMessage.class, clientOutMessages.getFirst());

        // Only send the FinishedMessage.
        serverEngine.onMessage(EncryptionLevel.HANDSHAKE, clientOutMessages.get(1));
        clientOutMessages.clear();

        Throwable failure = serverHandshake.get();
        // TODO: this fails for the wrong reason: the client Transcript contains the Certificate
        //  that we don't send to the server.
        assertInstanceOf(TLSException.class, failure);
    }

    @Test
    public void testWantClientAuthenticationEmptyCertificate()
    {
        serverEngine.getTLSConfiguration().getSslContextFactory().setWantClientAuth(true);

        clientEngine.startHandshake(clientTLSConfiguration, Callback.NOOP);
        serverEngine.onMessage(EncryptionLevel.INITIAL, clientOutMessages.getFirst());
        clientOutMessages.clear();

        // Server emits ServerHello, EncryptedExtensions, CertificateRequest, Certificate, CertificateVerify, Finished.
        assertThat(serverOutMessages.size(), equalTo(6));
        for (int i = 0; i < serverOutMessages.size(); ++i)
        {
            Message serverOutMessage = serverOutMessages.get(i);
            EncryptionLevel encryptionLevel = (i == 0) ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            clientEngine.onMessage(encryptionLevel, serverOutMessage);
        }
        serverOutMessages.clear();

        // Client emits an empty CertificateMessage and Finished.
        assertThat(clientOutMessages.size(), equalTo(2));
        for (Message clientOutMessage : clientOutMessages)
        {
            serverEngine.onMessage(EncryptionLevel.HANDSHAKE, clientOutMessage);
        }
        clientOutMessages.clear();

        // Server emits NewSessionTicket.
        assertThat(serverOutMessages.size(), equalTo(1));
        clientEngine.onMessage(EncryptionLevel.ONE_RTT, serverOutMessages.getFirst());
        serverOutMessages.clear();

        // No more messages emitted by the client.
        assertThat(clientOutMessages.size(), equalTo(0));

        assertSame(serverHandshake.get(), success);
        assertSame(clientHandshake.get(), success);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClientAuthenticationWithCertificate(boolean needAuth) throws Exception
    {
        SslContextFactory.Server serverTLS = serverEngine.getTLSConfiguration().getSslContextFactory();
        if (needAuth)
            serverTLS.setNeedClientAuth(true);
        else
            serverTLS.setWantClientAuth(true);

        SslContextFactory.Client clientTLS = clientTLSConfiguration.getSslContextFactory();
        clientTLS.reload(tls ->
        {
            tls.setKeyStorePath(MavenPaths.findTestResourceFile("client_keystore.p12"));
            tls.setKeyStorePassword("storepwd");
        });

        clientEngine.startHandshake(clientTLSConfiguration, Callback.NOOP);
        serverEngine.onMessage(EncryptionLevel.INITIAL, clientOutMessages.getFirst());
        clientOutMessages.clear();

        // Server emits ServerHello, EncryptedExtensions, CertificateRequest, Certificate, CertificateVerify, Finished.
        assertThat(serverOutMessages.size(), equalTo(6));
        for (int i = 0; i < serverOutMessages.size(); ++i)
        {
            Message serverOutMessage = serverOutMessages.get(i);
            EncryptionLevel encryptionLevel = (i == 0) ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            clientEngine.onMessage(encryptionLevel, serverOutMessage);
        }
        serverOutMessages.clear();

        // Client emits CertificateMessage, CertificateVerify and Finished.
        assertThat(clientOutMessages.size(), equalTo(3));
        for (Message clientOutMessage : clientOutMessages)
        {
            serverEngine.onMessage(EncryptionLevel.HANDSHAKE, clientOutMessage);
        }
        clientOutMessages.clear();

        // Server emits NewSessionTicket.
        assertThat(serverOutMessages.size(), equalTo(1));
        clientEngine.onMessage(EncryptionLevel.ONE_RTT, serverOutMessages.getFirst());
        serverOutMessages.clear();

        // No more messages emitted by the client.
        assertThat(clientOutMessages.size(), equalTo(0));

        assertSame(serverHandshake.get(), success);
        assertSame(clientHandshake.get(), success);
    }
}
