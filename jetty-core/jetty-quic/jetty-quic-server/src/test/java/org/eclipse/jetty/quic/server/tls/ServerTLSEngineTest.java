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

package org.eclipse.jetty.quic.server.tls;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSConfiguration;
import org.eclipse.jetty.quic.server.internal.tls.ServerTLSEngine;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.ConstantThrowable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ServerTLSEngineTest
{
    private final Throwable success = new ConstantThrowable();
    private final AtomicReference<Throwable> handshake = new AtomicReference<>();
    private ServerTLSEngine engine;
    private List<Message> outMessages;

    @BeforeEach
    public void prepare()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        PacketNumbers packetNumbers = new PacketNumbers();
        TranscriptHash transcriptHash = new TranscriptHash(byteBufferPool, new QuicMessagesGenerator(byteBufferPool, true), new QuicMessagesGenerator(byteBufferPool, false));
        PacketProtector packetProtector = new PacketProtector(byteBufferPool, packetNumbers, transcriptHash, false);
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12"));
        sslContextFactory.setKeyStorePassword("storepwd");
        ServerTLSConfiguration configuration = new ServerTLSConfiguration(new QuicServerQuicConfiguration(), sslContextFactory);
        engine = new ServerTLSEngine(packetProtector, configuration);

        outMessages = new ArrayList<>();
        engine.addMessageListener((_, msgs, callback) ->
        {
            outMessages.addAll(msgs);
            callback.succeeded();
        });

        engine.addHandshakeListener((_, failure) -> handshake.set(failure == null ? success : failure));
    }

    @Test
    public void testMissingSupportedVersionsExtension()
    {
        ClientHelloMessage message = new ClientHelloMessage(new byte[32], List.of(CipherSuite.TLS_AES_128_GCM_SHA256), List.of(new ALPNExtension(List.of("http/1.1"))));
        engine.onMessageParsed(message);

        assertThat(outMessages, empty());
        assertHandshakeFailed(TLSException.Alert.MISSING_EXTENSION);
    }

    @Test
    public void testNoCommonVersion()
    {
        ClientHelloMessage message = new ClientHelloMessage(new byte[32], List.of(CipherSuite.TLS_AES_128_GCM_SHA256), List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_2))));
        engine.onMessageParsed(message);

        assertThat(outMessages, empty());
        assertHandshakeFailed(TLSException.Alert.ILLEGAL_PARAMETER);
    }

    @Test
    public void testNoCommonCipherSuites()
    {
        engine.getTLSConfiguration().getServerQuicConfiguration().setCipherSuites(List.of(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));

        ClientHelloMessage message = new ClientHelloMessage(new byte[32], List.of(CipherSuite.TLS_AES_128_GCM_SHA256), List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        engine.onMessageParsed(message);

        assertThat(outMessages, empty());
        assertHandshakeFailed(TLSException.Alert.ILLEGAL_PARAMETER);
    }

    private void assertHandshakeFailed(TLSException.Alert alert)
    {
        Throwable failure = await().atMost(5, TimeUnit.SECONDS).until(handshake::get, notNullValue());
        assertInstanceOf(TLSException.class, failure);
        TLSException tlsException = (TLSException)failure;
        assertSame(tlsException.getAlert(), alert);
    }

    // TODO: and so on.
}
