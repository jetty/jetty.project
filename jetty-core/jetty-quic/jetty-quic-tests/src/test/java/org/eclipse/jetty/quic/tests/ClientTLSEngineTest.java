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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConstantThrowable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientTLSEngineTest
{
    private final Throwable success = new ConstantThrowable();
    private final AtomicReference<Throwable> handshake = new AtomicReference<>();
    private ClientTLSConfiguration configuration;
    private ClientTLSEngine engine;
    private List<Message> outMessages;

    @BeforeEach
    public void prepare()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        PacketNumbers packetNumbers = new PacketNumbers();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, false), new QuicMessagesGenerator(byteBufferPool, true));
        PacketProtector packetProtector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, true);
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        QuicClientQuicConfiguration quicConfig = new QuicClientQuicConfiguration();
        TransportParameters transportParameters = new TransportParameters();
        quicConfig.configure(transportParameters);
        configuration = new ClientTLSConfiguration(quicConfig, sslContextFactory, new DefaultZeroRTTStore());
        configuration.setTransportParameters(transportParameters);
        // TODO: parametrize on the version.
        configuration.setQuicVersion(QuicVersion.V1);
        configuration.setInputKeyMaterial(new byte[12]);
        engine = new ClientTLSEngine(packetProtector);

        outMessages = new ArrayList<>();
        engine.addMessageListener(new TLSEngine.MessageListener()
        {
            @Override
            public void onOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
            {
                outMessages.addAll(messages);
                callback.succeeded();
            }
        });

        engine.addHandshakeListener((_, failure) -> handshake.set(failure == null ? success : failure));
    }

    @Test
    public void testStartHandshake() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        engine.startHandshake(configuration, Callback.from(latch::countDown, _ -> {}));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(handshake.get(), nullValue());

        assertThat(outMessages.size(), is(1));
        ClientHelloMessage clientHello = (ClientHelloMessage)outMessages.getFirst();

        assertThat(clientHello.random().length, is(32));
        assertThat(clientHello.cipherSuites().size(), greaterThan(0));
        List<Extension> extensions = clientHello.extensions();
        assertThat(extensions.size(), greaterThan(0));
        assertTrue(extensions.stream().anyMatch(e -> e instanceof KeyShareExtension));
        assertTrue(extensions.stream().anyMatch(e -> e instanceof SignatureAlgorithmsExtension));
        assertTrue(extensions.stream().anyMatch(e -> e instanceof SupportedGroupsExtension));
        assertTrue(extensions.stream().anyMatch(e -> e instanceof SupportedVersionsExtension));
    }

    // TODO: test bad ServerHello received.
}
