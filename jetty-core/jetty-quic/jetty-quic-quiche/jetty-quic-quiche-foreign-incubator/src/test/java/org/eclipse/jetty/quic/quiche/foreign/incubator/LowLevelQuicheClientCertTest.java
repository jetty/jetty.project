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

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.eclipse.jetty.quic.quiche.Quiche.QUICHE_MIN_CLIENT_INITIAL_LEN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

@ExtendWith(WorkDirExtension.class)
public class LowLevelQuicheClientCertTest
{
    public WorkDir workDir;

    private final Collection<ForeignIncubatorQuicheConnection> connectionsToDisposeOf = new ArrayList<>();

    private InetSocketAddress clientSocketAddress;
    private InetSocketAddress serverSocketAddress;
    private QuicheConfig clientQuicheConfig;
    private QuicheConfig serverQuicheConfig;
    private ForeignIncubatorQuicheConnection.TokenMinter tokenMinter;
    private ForeignIncubatorQuicheConnection.TokenValidator tokenValidator;
    private Certificate[] serverCertificateChain;

    @BeforeEach
    protected void setUp() throws Exception
    {
        clientSocketAddress = new InetSocketAddress("localhost", 9999);
        serverSocketAddress = new InetSocketAddress("localhost", 8888);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        Path targetFolder = workDir.getEmptyPathDir();
        Path[] keyPair = PemExporter.exportKeyPair(keyStore, "mykey", "storepwd".toCharArray(), targetFolder);
        Path trustStorePath = PemExporter.exportTrustStore(keyStore, targetFolder);

        clientQuicheConfig = new QuicheConfig();
        clientQuicheConfig.setApplicationProtos("http/0.9");
        clientQuicheConfig.setDisableActiveMigration(true);
        clientQuicheConfig.setPrivKeyPemPath(keyPair[0].toString());
        clientQuicheConfig.setCertChainPemPath(keyPair[1].toString());
        clientQuicheConfig.setVerifyPeer(true);
        clientQuicheConfig.setTrustedCertsPemPath(trustStorePath.toString());
        clientQuicheConfig.setMaxIdleTimeout(1_000L);
        clientQuicheConfig.setInitialMaxData(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataBidiLocal(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataBidiRemote(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataUni(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamsUni(100L);
        clientQuicheConfig.setInitialMaxStreamsBidi(100L);
        clientQuicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);

        serverCertificateChain = keyStore.getCertificateChain("mykey");
        serverQuicheConfig = new QuicheConfig();
        serverQuicheConfig.setPrivKeyPemPath(keyPair[0].toString());
        serverQuicheConfig.setCertChainPemPath(keyPair[1].toString());
        serverQuicheConfig.setApplicationProtos("http/0.9");
        serverQuicheConfig.setVerifyPeer(true);
        serverQuicheConfig.setTrustedCertsPemPath(trustStorePath.toString());
        serverQuicheConfig.setMaxIdleTimeout(1_000L);
        serverQuicheConfig.setInitialMaxData(10_000_000L);
        serverQuicheConfig.setInitialMaxStreamDataBidiLocal(10_000_000L);
        serverQuicheConfig.setInitialMaxStreamDataBidiRemote(10_000_000L);
        serverQuicheConfig.setInitialMaxStreamDataUni(10_000_000L);
        serverQuicheConfig.setInitialMaxStreamsUni(100L);
        serverQuicheConfig.setInitialMaxStreamsBidi(100L);
        serverQuicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);

        tokenMinter = new TestTokenMinter();
        tokenValidator = new TestTokenValidator();
    }

    @AfterEach
    protected void tearDown()
    {
        connectionsToDisposeOf.forEach(ForeignIncubatorQuicheConnection::dispose);
        connectionsToDisposeOf.clear();
    }

    @Test
    public void testClientCert() throws Exception
    {
        // establish connection
        Map.Entry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> entry = connectClientToServer();
        ForeignIncubatorQuicheConnection clientQuicheConnection = entry.getKey();
        ForeignIncubatorQuicheConnection serverQuicheConnection = entry.getValue();

        // assert that the client certificate was correctly received by the server
        byte[] receivedClientCertificate = serverQuicheConnection.getPeerCertificate();
        byte[] configuredClientCertificate = serverCertificateChain[0].getEncoded();
        assertThat(Arrays.equals(configuredClientCertificate, receivedClientCertificate), is(true));

        // assert that the server certificate was correctly received by the client
        byte[] receivedServerCertificate = clientQuicheConnection.getPeerCertificate();
        byte[] configuredServerCertificate = serverCertificateChain[0].getEncoded();
        assertThat(Arrays.equals(configuredServerCertificate, receivedServerCertificate), is(true));
    }

    private void drainServerToFeedClient(Map.Entry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> entry, int expectedSize) throws IOException
    {
        ForeignIncubatorQuicheConnection clientQuicheConnection = entry.getKey();
        ForeignIncubatorQuicheConnection serverQuicheConnection = entry.getValue();
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        int drained = serverQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(expectedSize));
        buffer.flip();
        int fed = clientQuicheConnection.feedCipherBytes(buffer, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(expectedSize));
    }

    private void drainServerToFeedClient(Map.Entry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> entry, int expectedSizeLowerBound, int expectedSizeUpperBound) throws IOException
    {
        ForeignIncubatorQuicheConnection clientQuicheConnection = entry.getKey();
        ForeignIncubatorQuicheConnection serverQuicheConnection = entry.getValue();
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        int drained = serverQuicheConnection.drainCipherBytes(buffer);

        assertThat(drained, is(both(greaterThanOrEqualTo(expectedSizeLowerBound)).and(lessThanOrEqualTo(expectedSizeUpperBound))));
        buffer.flip();
        int fed = clientQuicheConnection.feedCipherBytes(buffer, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(both(greaterThanOrEqualTo(expectedSizeLowerBound)).and(lessThanOrEqualTo(expectedSizeUpperBound))));
    }

    private void drainClientToFeedServer(Map.Entry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> entry, int expectedSize) throws IOException
    {
        ForeignIncubatorQuicheConnection clientQuicheConnection = entry.getKey();
        ForeignIncubatorQuicheConnection serverQuicheConnection = entry.getValue();
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        int drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(expectedSize));
        buffer.flip();
        int fed = serverQuicheConnection.feedCipherBytes(buffer, serverSocketAddress, clientSocketAddress);
        assertThat(fed, is(expectedSize));
    }

    private Map.Entry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> connectClientToServer() throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);
        ByteBuffer buffer2 = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        ForeignIncubatorQuicheConnection clientQuicheConnection = ForeignIncubatorQuicheConnection.connect(clientQuicheConfig, clientSocketAddress, serverSocketAddress);
        connectionsToDisposeOf.add(clientQuicheConnection);

        int drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(1200));
        buffer.flip();

        ForeignIncubatorQuicheConnection serverQuicheConnection = ForeignIncubatorQuicheConnection.tryAccept(serverQuicheConfig, tokenValidator, buffer, serverSocketAddress, clientSocketAddress);
        assertThat(serverQuicheConnection, is(nullValue()));
        boolean negotiated = ForeignIncubatorQuicheConnection.negotiate(tokenMinter, buffer, buffer2);
        assertThat(negotiated, is(true));
        buffer2.flip();

        int fed = clientQuicheConnection.feedCipherBytes(buffer2, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(79));

        buffer.clear();
        drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(1200));
        buffer.flip();

        serverQuicheConnection = ForeignIncubatorQuicheConnection.tryAccept(serverQuicheConfig, tokenValidator, buffer, serverSocketAddress, clientSocketAddress);
        assertThat(serverQuicheConnection, is(not(nullValue())));
        connectionsToDisposeOf.add(serverQuicheConnection);

        buffer.clear();
        drained = serverQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(1200));
        buffer.flip();

        fed = clientQuicheConnection.feedCipherBytes(buffer, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(1200));

        assertThat(serverQuicheConnection.isConnectionEstablished(), is(false));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(false));

        AbstractMap.SimpleImmutableEntry<ForeignIncubatorQuicheConnection, ForeignIncubatorQuicheConnection> entry = new AbstractMap.SimpleImmutableEntry<>(clientQuicheConnection, serverQuicheConnection);

        int protosLen = 0;
        for (String proto : clientQuicheConfig.getApplicationProtos())
            protosLen += 1 + proto.getBytes(StandardCharsets.UTF_8).length;

        // 1st round
        drainServerToFeedClient(entry, 451 + protosLen);
        assertThat(serverQuicheConnection.isConnectionEstablished(), is(false));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(true));

        drainClientToFeedServer(entry, 1200);
        assertThat(serverQuicheConnection.isConnectionEstablished(), is(false));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(true));

        // 2nd round (needed b/c of client cert)
        drainServerToFeedClient(entry, 71, 72);
        assertThat(serverQuicheConnection.isConnectionEstablished(), is(false));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(true));

        drainClientToFeedServer(entry, 222);
        assertThat(serverQuicheConnection.isConnectionEstablished(), is(true));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(true));

        return entry;
    }

    private static class TestTokenMinter implements QuicheConnection.TokenMinter
    {
        @Override
        public byte[] mint(byte[] dcid, int len)
        {
            return ByteBuffer.allocate(len).put(dcid, 0, len).array();
        }
    }

    private static class TestTokenValidator implements QuicheConnection.TokenValidator
    {
        @Override
        public byte[] validate(byte[] token, int len)
        {
            return ByteBuffer.allocate(len).put(token, 0, len).array();
        }
    }
}
