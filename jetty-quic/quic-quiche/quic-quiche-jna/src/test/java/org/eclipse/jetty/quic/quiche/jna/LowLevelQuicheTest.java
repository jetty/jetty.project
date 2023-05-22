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

package org.eclipse.jetty.quic.quiche.jna;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.SSLKeyPair;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.eclipse.jetty.quic.quiche.Quiche.QUICHE_MIN_CLIENT_INITIAL_LEN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

@ExtendWith(WorkDirExtension.class)
public class LowLevelQuicheTest
{
    public WorkDir workDir;

    private final Collection<JnaQuicheConnection> connectionsToDisposeOf = new ArrayList<>();

    private InetSocketAddress clientSocketAddress;
    private InetSocketAddress serverSocketAddress;
    private QuicheConfig clientQuicheConfig;
    private QuicheConfig serverQuicheConfig;
    private JnaQuicheConnection.TokenMinter tokenMinter;
    private JnaQuicheConnection.TokenValidator tokenValidator;

    @BeforeEach
    protected void setUp() throws Exception
    {
        clientSocketAddress = new InetSocketAddress("localhost", 9999);
        serverSocketAddress = new InetSocketAddress("localhost", 8888);

        clientQuicheConfig = new QuicheConfig();
        clientQuicheConfig.setApplicationProtos("http/0.9");
        clientQuicheConfig.setDisableActiveMigration(true);
        clientQuicheConfig.setVerifyPeer(false);
        clientQuicheConfig.setMaxIdleTimeout(1_000L);
        clientQuicheConfig.setInitialMaxData(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataBidiLocal(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataBidiRemote(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamDataUni(10_000_000L);
        clientQuicheConfig.setInitialMaxStreamsUni(100L);
        clientQuicheConfig.setInitialMaxStreamsBidi(100L);
        clientQuicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        SSLKeyPair serverKeyPair = new SSLKeyPair(keyStore, "mykey", "storepwd".toCharArray());
        Path[] pemFiles = serverKeyPair.export(workDir.getEmptyPathDir());
        serverQuicheConfig = new QuicheConfig();
        serverQuicheConfig.setPrivKeyPemPath(pemFiles[0].toString());
        serverQuicheConfig.setCertChainPemPath(pemFiles[1].toString());
        serverQuicheConfig.setApplicationProtos("http/0.9");
        serverQuicheConfig.setVerifyPeer(false);
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
        connectionsToDisposeOf.forEach(JnaQuicheConnection::dispose);
        connectionsToDisposeOf.clear();
    }

    @Test
    public void testFinishedAsSoonAsFinIsFed() throws Exception
    {
        // establish connection
        Map.Entry<JnaQuicheConnection, JnaQuicheConnection> entry = connectClientToServer();
        JnaQuicheConnection clientQuicheConnection = entry.getKey();
        JnaQuicheConnection serverQuicheConnection = entry.getValue();

        // client sends 16 bytes of payload over stream 0
        assertThat(clientQuicheConnection.feedClearBytesForStream(0, ByteBuffer.allocate(16)
            .putInt(0xdeadbeef)
            .putInt(0xcafebabe)
            .putInt(0xdeadc0de)
            .putInt(0xbaddecaf)
            .flip()), is(16));
        drainClientToFeedServer(entry, 59);

        // server checks that stream 0 is readable
        List<Long> readableStreamIds = serverQuicheConnection.readableStreamIds();
        assertThat(readableStreamIds.size(), is(1));
        assertThat(readableStreamIds.get(0), is(0L));

        // server reads 16 bytes from stream 0
        assertThat(serverQuicheConnection.drainClearBytesForStream(0, ByteBuffer.allocate(1000)), is(16));

        // assert that stream 0 is not finished on server
        assertThat(serverQuicheConnection.isStreamFinished(0), is(false));

        // client finishes stream 0
        clientQuicheConnection.feedFinForStream(0);

        drainClientToFeedServer(entry, 43);
        readableStreamIds = serverQuicheConnection.readableStreamIds();
        assertThat(readableStreamIds.size(), is(1));
        assertThat(readableStreamIds.get(0), is(0L));

        // assert that stream 0 is finished on server
        assertThat(serverQuicheConnection.isStreamFinished(0), is(true));
    }

    @Test
    public void testNotFinishedAsLongAsStreamHasReadableBytes() throws Exception
    {
        // establish connection
        Map.Entry<JnaQuicheConnection, JnaQuicheConnection> entry = connectClientToServer();
        JnaQuicheConnection clientQuicheConnection = entry.getKey();
        JnaQuicheConnection serverQuicheConnection = entry.getValue();

        // client sends 16 bytes of payload over stream 0 and finish it
        assertThat(clientQuicheConnection.feedClearBytesForStream(0, ByteBuffer.allocate(16)
            .putInt(0xdeadbeef)
            .putInt(0xcafebabe)
            .putInt(0xdeadc0de)
            .putInt(0xbaddecaf)
            .flip()), is(16));
        clientQuicheConnection.feedFinForStream(0);
        drainClientToFeedServer(entry, 59);

        // server checks that stream 0 is readable
        List<Long> readableStreamIds = serverQuicheConnection.readableStreamIds();
        assertThat(readableStreamIds.size(), is(1));
        assertThat(readableStreamIds.get(0), is(0L));

        // assert that stream 0 is not finished on server
        assertThat(serverQuicheConnection.isStreamFinished(0), is(false));

        // server reads 16 bytes from stream 0
        assertThat(serverQuicheConnection.drainClearBytesForStream(0, ByteBuffer.allocate(1000)), is(16));

        // assert that stream 0 is finished on server
        assertThat(serverQuicheConnection.isStreamFinished(0), is(true));
    }

    @Test
    public void testApplicationProtocol() throws Exception
    {
        serverQuicheConfig.setApplicationProtos("€");
        clientQuicheConfig.setApplicationProtos("€");

        // establish connection
        Map.Entry<JnaQuicheConnection, JnaQuicheConnection> entry = connectClientToServer();
        JnaQuicheConnection clientQuicheConnection = entry.getKey();
        JnaQuicheConnection serverQuicheConnection = entry.getValue();

        assertThat(clientQuicheConnection.getNegotiatedProtocol(), is("€"));
        assertThat(serverQuicheConnection.getNegotiatedProtocol(), is("€"));
    }

    private void drainServerToFeedClient(Map.Entry<JnaQuicheConnection, JnaQuicheConnection> entry, int expectedSize) throws IOException
    {
        JnaQuicheConnection clientQuicheConnection = entry.getKey();
        JnaQuicheConnection serverQuicheConnection = entry.getValue();
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        int drained = serverQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(expectedSize));
        buffer.flip();
        int fed = clientQuicheConnection.feedCipherBytes(buffer, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(expectedSize));
    }

    private void drainClientToFeedServer(Map.Entry<JnaQuicheConnection, JnaQuicheConnection> entry, int expectedSize) throws IOException
    {
        JnaQuicheConnection clientQuicheConnection = entry.getKey();
        JnaQuicheConnection serverQuicheConnection = entry.getValue();
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        int drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(expectedSize));
        buffer.flip();
        int fed = serverQuicheConnection.feedCipherBytes(buffer, serverSocketAddress, clientSocketAddress);
        assertThat(fed, is(expectedSize));
    }

    private Map.Entry<JnaQuicheConnection, JnaQuicheConnection> connectClientToServer() throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);
        ByteBuffer buffer2 = ByteBuffer.allocate(QUICHE_MIN_CLIENT_INITIAL_LEN);

        JnaQuicheConnection clientQuicheConnection = JnaQuicheConnection.connect(clientQuicheConfig, clientSocketAddress, serverSocketAddress);
        connectionsToDisposeOf.add(clientQuicheConnection);

        int drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(1200));
        buffer.flip();

        JnaQuicheConnection serverQuicheConnection = JnaQuicheConnection.tryAccept(serverQuicheConfig, tokenValidator, buffer, serverSocketAddress, clientSocketAddress);
        assertThat(serverQuicheConnection, is(nullValue()));
        boolean negotiated = JnaQuicheConnection.negotiate(tokenMinter, buffer, buffer2);
        assertThat(negotiated, is(true));
        buffer2.flip();

        int fed = clientQuicheConnection.feedCipherBytes(buffer2, clientSocketAddress, serverSocketAddress);
        assertThat(fed, is(79));

        buffer.clear();
        drained = clientQuicheConnection.drainCipherBytes(buffer);
        assertThat(drained, is(1200));
        buffer.flip();

        serverQuicheConnection = JnaQuicheConnection.tryAccept(serverQuicheConfig, tokenValidator, buffer, serverSocketAddress, clientSocketAddress);
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

        AbstractMap.SimpleImmutableEntry<JnaQuicheConnection, JnaQuicheConnection> entry = new AbstractMap.SimpleImmutableEntry<>(clientQuicheConnection, serverQuicheConnection);

        int protosLen = 0;
        for (String proto : clientQuicheConfig.getApplicationProtos())
            protosLen += 1 + proto.getBytes(LibQuiche.CHARSET).length;

        drainServerToFeedClient(entry, 300 + protosLen);
        assertThat(serverQuicheConnection.isConnectionEstablished(), is(false));
        assertThat(clientQuicheConnection.isConnectionEstablished(), is(true));

        drainClientToFeedServer(entry, 1200);
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
