//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.quiche.panama.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.quic.quiche.panama.jdk.quiche_h.C_FALSE;
import static org.eclipse.jetty.quic.quiche.panama.jdk.quiche_h.C_TRUE;

public class PanamaJdkQuicheConnection extends QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(PanamaJdkQuicheConnection.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Quiche does not allow concurrent calls with the same connection.
    private final AutoLock lock = new AutoLock();
    private MemoryAddress quicheConn;
    private MemoryAddress quicheConfig;
    private ResourceScope scope;
    private MemorySegment sendInfo;
    private MemorySegment recvInfo;
    private MemorySegment stats;

    private PanamaJdkQuicheConnection(MemoryAddress quicheConn, MemoryAddress quicheConfig, ResourceScope scope)
    {
        this.quicheConn = quicheConn;
        this.quicheConfig = quicheConfig;
        this.scope = scope;
        this.sendInfo = quiche_send_info.allocate(scope);
        this.recvInfo = quiche_recv_info.allocate(scope);
        this.stats = quiche_stats.allocate(scope);
    }

    public static byte[] fromPacket(ByteBuffer packet)
    {
        try (ResourceScope scope = ResourceScope.newConfinedScope())
        {
            MemorySegment type = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
            MemorySegment version = MemorySegment.allocateNative(CLinker.C_INT, scope);

            // Source Connection ID
            MemorySegment scid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
            MemorySegment scid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(scid.byteSize());

            // Destination Connection ID
            MemorySegment dcid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
            MemorySegment dcid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(dcid.byteSize());

            MemorySegment token = MemorySegment.allocateNative(QuicheConnection.TokenMinter.MAX_TOKEN_LENGTH, scope);
            MemorySegment token_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            token_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(token.byteSize());

            LOG.debug("getting header info (fromPacket)...");
            int rc;

            if (packet.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                MemorySegment packetReadSegment = MemorySegment.ofByteBuffer(packet);
                rc = quiche_h.quiche_header_info(packetReadSegment.address(), packet.remaining(), quiche_h.QUICHE_MAX_CONN_ID_LEN,
                    version.address(), type.address(),
                    scid.address(), scid_len.address(),
                    dcid.address(), dcid_len.address(),
                    token.address(), token_len.address());
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                MemorySegment packetReadSegment = MemorySegment.allocateNative(packet.remaining(), scope);
                int prevPosition = packet.position();
                packetReadSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(packet);
                packet.position(prevPosition);
                rc = quiche_h.quiche_header_info(packetReadSegment.address(), packet.remaining(), quiche_h.QUICHE_MAX_CONN_ID_LEN,
                    version.address(), type.address(),
                    scid.address(), scid_len.address(),
                    dcid.address(), dcid_len.address(),
                    token.address(), token_len.address());
            }
            if (rc < 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("quiche cannot read header info from packet {}", BufferUtil.toDetailString(packet));
                return null;
            }

            byte[] bytes = new byte[(int)dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong()];
            dcid.asByteBuffer().order(ByteOrder.nativeOrder()).get(bytes);
            return bytes;
        }
    }

    public static PanamaJdkQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer) throws IOException
    {
        return connect(quicheConfig, peer, quiche_h.QUICHE_MAX_CONN_ID_LEN);
    }

    public static PanamaJdkQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        if (connectionIdLength > quiche_h.QUICHE_MAX_CONN_ID_LEN)
            throw new IOException("Connection ID length is too large: " + connectionIdLength + " > " + quiche_h.QUICHE_MAX_CONN_ID_LEN);
        ResourceScope scope = ResourceScope.newSharedScope();
        byte[] scidBytes = new byte[connectionIdLength];
        SECURE_RANDOM.nextBytes(scidBytes);
        MemorySegment scid = MemorySegment.allocateNative(scidBytes.length, scope);
        scid.asByteBuffer().order(ByteOrder.nativeOrder()).put(scidBytes);
        MemoryAddress libQuicheConfig = buildConfig(quicheConfig, scope);

        MemorySegment s = sockaddr.convert(peer, scope);
        MemoryAddress quicheConn = quiche_h.quiche_connect(CLinker.toCString(peer.getHostName(), scope), scid, scid.byteSize(), s, s.byteSize(), libQuicheConfig);
        return new PanamaJdkQuicheConnection(quicheConn, libQuicheConfig, scope);
    }

    private static MemoryAddress buildConfig(QuicheConfig config, ResourceScope scope) throws IOException
    {
        MemoryAddress quicheConfig = quiche_h.quiche_config_new(config.getVersion());
        if (quicheConfig == null)
            throw new IOException("Failed to create quiche config");

        Boolean verifyPeer = config.getVerifyPeer();
        if (verifyPeer != null)
            quiche_h.quiche_config_verify_peer(quicheConfig, verifyPeer ? C_TRUE : C_FALSE);

        String certChainPemPath = config.getCertChainPemPath();
        if (certChainPemPath != null)
            quiche_h.quiche_config_load_cert_chain_from_pem_file(quicheConfig, CLinker.toCString(certChainPemPath, scope).address());

        String privKeyPemPath = config.getPrivKeyPemPath();
        if (privKeyPemPath != null)
            quiche_h.quiche_config_load_priv_key_from_pem_file(quicheConfig, CLinker.toCString(privKeyPemPath, scope).address());

        String[] applicationProtos = config.getApplicationProtos();
        if (applicationProtos != null)
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (String proto : applicationProtos)
            {
                byte[] bytes = proto.getBytes(StandardCharsets.UTF_8);
                baos.write(bytes.length);
                baos.write(bytes);
            }
            byte[] bytes = baos.toByteArray();
            MemorySegment segment = MemorySegment.allocateNative(bytes.length, scope);
            segment.asByteBuffer().order(ByteOrder.nativeOrder()).put(bytes);
            quiche_h.quiche_config_set_application_protos(quicheConfig, segment.address(), segment.byteSize());
        }

        QuicheConfig.CongestionControl cc = config.getCongestionControl();
        if (cc != null)
            quiche_h.quiche_config_set_cc_algorithm(quicheConfig, cc.getValue());

        Long maxIdleTimeout = config.getMaxIdleTimeout();
        if (maxIdleTimeout != null)
            quiche_h.quiche_config_set_max_idle_timeout(quicheConfig, maxIdleTimeout);

        Long initialMaxData = config.getInitialMaxData();
        if (initialMaxData != null)
            quiche_h.quiche_config_set_initial_max_data(quicheConfig, initialMaxData);

        Long initialMaxStreamDataBidiLocal = config.getInitialMaxStreamDataBidiLocal();
        if (initialMaxStreamDataBidiLocal != null)
            quiche_h.quiche_config_set_initial_max_stream_data_bidi_local(quicheConfig, initialMaxStreamDataBidiLocal);

        Long initialMaxStreamDataBidiRemote = config.getInitialMaxStreamDataBidiRemote();
        if (initialMaxStreamDataBidiRemote != null)
            quiche_h.quiche_config_set_initial_max_stream_data_bidi_remote(quicheConfig, initialMaxStreamDataBidiRemote);

        Long initialMaxStreamDataUni = config.getInitialMaxStreamDataUni();
        if (initialMaxStreamDataUni != null)
            quiche_h.quiche_config_set_initial_max_stream_data_uni(quicheConfig, initialMaxStreamDataUni);

        Long initialMaxStreamsBidi = config.getInitialMaxStreamsBidi();
        if (initialMaxStreamsBidi != null)
            quiche_h.quiche_config_set_initial_max_streams_bidi(quicheConfig, initialMaxStreamsBidi);

        Long initialMaxStreamsUni = config.getInitialMaxStreamsUni();
        if (initialMaxStreamsUni != null)
            quiche_h.quiche_config_set_initial_max_streams_uni(quicheConfig, initialMaxStreamsUni);

        Boolean disableActiveMigration = config.getDisableActiveMigration();
        if (disableActiveMigration != null)
            quiche_h.quiche_config_set_disable_active_migration(quicheConfig, disableActiveMigration ? C_TRUE : C_FALSE);

        return quicheConfig;
    }

    public static boolean negotiate(TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        try (ResourceScope scope = ResourceScope.newConfinedScope())
        {
            MemorySegment packetReadSegment;
            if (packetRead.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                packetReadSegment = MemorySegment.ofByteBuffer(packetRead);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                packetReadSegment = MemorySegment.allocateNative(packetRead.remaining(), scope);
                int prevPosition = packetRead.position();
                packetReadSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(packetRead);
                packetRead.position(prevPosition);
            }

            MemorySegment type = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
            MemorySegment version = MemorySegment.allocateNative(CLinker.C_INT, scope);

            // Source Connection ID
            MemorySegment scid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
            MemorySegment scid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(scid.byteSize());

            // Destination Connection ID
            MemorySegment dcid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
            MemorySegment dcid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(dcid.byteSize());

            MemorySegment token = MemorySegment.allocateNative(TokenMinter.MAX_TOKEN_LENGTH, scope);
            MemorySegment token_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            token_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(token.byteSize());

            LOG.debug("getting header info (negotiate)...");
            int rc = quiche_h.quiche_header_info(packetReadSegment.address(), packetRead.remaining(), quiche_h.QUICHE_MAX_CONN_ID_LEN,
                version.address(), type.address(),
                scid.address(), scid_len.address(),
                dcid.address(), dcid_len.address(),
                token.address(), token_len.address());
            if (rc < 0)
                throw new IOException("failed to parse header: " + quiche_h.quiche_error.errToString(rc));
            packetRead.position(packetRead.limit());

            LOG.debug("version: {}", version.asByteBuffer().order(ByteOrder.nativeOrder()).getInt());
            LOG.debug("type: {}", type.asByteBuffer().order(ByteOrder.nativeOrder()).get());
            LOG.debug("scid len: {}", scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
            LOG.debug("dcid len: {}", dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
            LOG.debug("token len: {}", token_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());

            if (quiche_h.quiche_version_is_supported(version.asByteBuffer().order(ByteOrder.nativeOrder()).getInt()) == C_FALSE)
            {
                LOG.debug("version negotiation");

                MemorySegment packetToSendSegment;
                if (packetToSend.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    packetToSendSegment = MemorySegment.ofByteBuffer(packetToSend);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    packetToSendSegment = MemorySegment.allocateNative(packetToSend.remaining(), scope);
                }

                long generated = quiche_h.quiche_negotiate_version(scid.address(), scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong(), dcid.address(), dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong(), packetToSendSegment.address(), packetToSend.remaining());
                if (generated < 0)
                    throw new IOException("failed to create vneg packet : " + quiche_h.quiche_error.errToString(generated));
                if (!packetToSend.isDirect())
                    packetToSend.put(packetToSendSegment.asByteBuffer().order(ByteOrder.nativeOrder()).limit((int)generated));
                else
                    packetToSend.position((int)(packetToSend.position() + generated));
                return true;
            }

            if (token_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong() == 0L)
            {
                LOG.debug("stateless retry");

                byte[] dcidBytes = new byte[(int)dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong()];
                dcid.asByteBuffer().order(ByteOrder.nativeOrder()).get(dcidBytes);
                byte[] tokenBytes = tokenMinter.mint(dcidBytes, dcidBytes.length);
                token.asByteBuffer().order(ByteOrder.nativeOrder()).put(tokenBytes);

                byte[] newCid = new byte[quiche_h.QUICHE_MAX_CONN_ID_LEN];
                SECURE_RANDOM.nextBytes(newCid);
                MemorySegment newCidSegment = MemorySegment.allocateNative(newCid.length, scope);
                newCidSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(newCid);

                MemorySegment packetToSendSegment;
                if (packetToSend.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    packetToSendSegment = MemorySegment.ofByteBuffer(packetToSend);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    packetToSendSegment = MemorySegment.allocateNative(packetToSend.remaining(), scope);
                }

                long generated = quiche_h.quiche_retry(scid.address(), scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong(),
                    dcid.address(), dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong(),
                    newCidSegment.address(), newCid.length,
                    token.address(), tokenBytes.length,
                    version.asByteBuffer().order(ByteOrder.nativeOrder()).getInt(),
                    packetToSendSegment.address(), packetToSendSegment.byteSize()
                );
                if (generated < 0)
                    throw new IOException("failed to create retry packet: " + quiche_h.quiche_error.errToString(generated));
                if (!packetToSend.isDirect())
                    packetToSend.put(packetToSendSegment.asByteBuffer().order(ByteOrder.nativeOrder()).limit((int)generated));
                else
                    packetToSend.position((int)(packetToSend.position() + generated));
                return true;
            }

            return false;
        }
    }

    public static PanamaJdkQuicheConnection tryAccept(QuicheConfig quicheConfig, TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress peer) throws IOException
    {
        ResourceScope scope = ResourceScope.newSharedScope();

        MemorySegment type = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
        MemorySegment version = MemorySegment.allocateNative(CLinker.C_INT, scope);

        // Source Connection ID
        MemorySegment scid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
        MemorySegment scid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
        scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(scid.byteSize());

        // Destination Connection ID
        MemorySegment dcid = MemorySegment.allocateNative(quiche_h.QUICHE_MAX_CONN_ID_LEN, scope);
        MemorySegment dcid_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
        dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(dcid.byteSize());

        MemorySegment token = MemorySegment.allocateNative(TokenMinter.MAX_TOKEN_LENGTH, scope);
        MemorySegment token_len = MemorySegment.allocateNative(CLinker.C_LONG, scope);
        token_len.asByteBuffer().order(ByteOrder.nativeOrder()).putLong(token.byteSize());

        LOG.debug("getting header info (tryAccept)...");
        int rc;

        if (packetRead.isDirect())
        {
            // If the ByteBuffer is direct, it can be used without any copy.
            MemorySegment packetReadSegment = MemorySegment.ofByteBuffer(packetRead);
            rc = quiche_h.quiche_header_info(packetReadSegment.address(), packetRead.remaining(), quiche_h.QUICHE_MAX_CONN_ID_LEN,
                version.address(), type.address(),
                scid.address(), scid_len.address(),
                dcid.address(), dcid_len.address(),
                token.address(), token_len.address());
        }
        else
        {
            // If the ByteBuffer is heap-allocated, it must be copied to native memory.
            try (ResourceScope segmentScope = ResourceScope.newConfinedScope())
            {
                MemorySegment packetReadSegment = MemorySegment.allocateNative(packetRead.remaining(), segmentScope);
                int prevPosition = packetRead.position();
                packetReadSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(packetRead);
                packetRead.position(prevPosition);
                rc = quiche_h.quiche_header_info(packetReadSegment.address(), packetRead.remaining(), quiche_h.QUICHE_MAX_CONN_ID_LEN,
                    version.address(), type.address(),
                    scid.address(), scid_len.address(),
                    dcid.address(), dcid_len.address(),
                    token.address(), token_len.address());
            }
        }
        if (rc < 0)
        {
            scope.close();
            throw new IOException("failed to parse header: " + quiche_h.quiche_error.errToString(rc));
        }

        LOG.debug("version: {}", version.asByteBuffer().order(ByteOrder.nativeOrder()).getInt());
        LOG.debug("type: {}", type.asByteBuffer().order(ByteOrder.nativeOrder()).get());
        LOG.debug("scid len: {}", scid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
        LOG.debug("dcid len: {}", dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
        LOG.debug("token len: {}", token_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());

        if (quiche_h.quiche_version_is_supported(version.asByteBuffer().order(ByteOrder.nativeOrder()).getInt()) == C_FALSE)
        {
            LOG.debug("need version negotiation");
            scope.close();
            return null;
        }

        int tokenLen = (int)token_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong();
        if (tokenLen == 0)
        {
            LOG.debug("need stateless retry");
            scope.close();
            return null;
        }

        LOG.debug("token validation...");
        // Original Destination Connection ID
        byte[] tokenBytes = new byte[(int)token.byteSize()];
        token.asByteBuffer().order(ByteOrder.nativeOrder()).get(tokenBytes, 0, tokenLen);
        byte[] odcidBytes = tokenValidator.validate(tokenBytes, tokenLen);
        if (odcidBytes == null)
        {
            scope.close();
            throw new TokenValidationException("invalid address validation token");
        }
        LOG.debug("validated token");
        MemorySegment odcid = MemorySegment.allocateNative(odcidBytes.length, scope);
        odcid.asByteBuffer().order(ByteOrder.nativeOrder()).put(odcidBytes);

        LOG.debug("connection creation...");
        MemoryAddress libQuicheConfig = buildConfig(quicheConfig, scope);

        MemorySegment s = sockaddr.convert(peer, scope);
        MemoryAddress quicheConn = quiche_h.quiche_accept(dcid.address(), dcid_len.asByteBuffer().order(ByteOrder.nativeOrder()).getLong(), odcid.address(), odcid.byteSize(), s.address(), s.byteSize(), libQuicheConfig);

        if (quicheConn == null)
        {
            quiche_h.quiche_config_free(libQuicheConfig);
            scope.close();
            throw new IOException("failed to create connection");
        }

        LOG.debug("connection created");
        PanamaJdkQuicheConnection quicheConnection = new PanamaJdkQuicheConnection(quicheConn, libQuicheConfig, scope);
        LOG.debug("accepted, immediately receiving the same packet - remaining in buffer: {}", packetRead.remaining());
        while (packetRead.hasRemaining())
        {
            quicheConnection.feedCipherBytes(packetRead, peer);
        }
        return quicheConnection;
    }

    @Override
    protected List<Long> iterableStreamIds(boolean write)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            MemoryAddress quiche_stream_iter;
            if (write)
                quiche_stream_iter = quiche_h.quiche_conn_writable(quicheConn);
            else
                quiche_stream_iter = quiche_h.quiche_conn_readable(quicheConn);

            List<Long> result = new ArrayList<>();
            try (ResourceScope scope = ResourceScope.newConfinedScope())
            {
                MemorySegment streamIdSegment = MemorySegment.allocateNative(CLinker.C_LONG, scope);
                while (quiche_h.quiche_stream_iter_next(quiche_stream_iter, streamIdSegment.address()) != C_FALSE)
                {
                    long streamId = streamIdSegment.asByteBuffer().order(ByteOrder.nativeOrder()).getLong();
                    result.add(streamId);
                }
            }

            quiche_h.quiche_stream_iter_free(quiche_stream_iter);
            return result;
        }
    }

    @Override
    public int feedCipherBytes(ByteBuffer buffer, SocketAddress peer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("Cannot receive when not connected");

            long received;
            try (ResourceScope scope = ResourceScope.newConfinedScope())
            {
                quiche_recv_info.setSocketAddress(recvInfo, peer, scope);
                if (buffer.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    MemorySegment bufferSegment = MemorySegment.ofByteBuffer(buffer);
                    received = quiche_h.quiche_conn_recv(quicheConn, bufferSegment.address(), buffer.remaining(), recvInfo.address());
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                    MemorySegment bufferSegment = MemorySegment.allocateNative(buffer.remaining(), scope);
                    int prevPosition = buffer.position();
                    bufferSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(buffer);
                    buffer.position(prevPosition);
                    received = quiche_h.quiche_conn_recv(quicheConn, bufferSegment.address(), buffer.remaining(), recvInfo.address());
                }
            }
            if (received < 0)
                throw new IOException("failed to receive packet; err=" + quiche_h.quiche_error.errToString(received));
            buffer.position((int)(buffer.position() + received));
            return (int)received;
        }
    }

    @Override
    public int drainCipherBytes(ByteBuffer buffer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("Cannot send when not connected");

            int prevPosition = buffer.position();
            long written;
            if (buffer.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                MemorySegment bufferSegment = MemorySegment.ofByteBuffer(buffer);
                written = quiche_h.quiche_conn_send(quicheConn, bufferSegment.address(), buffer.remaining(), sendInfo.address());
            }
            else
            {
                // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                try (ResourceScope scope = ResourceScope.newConfinedScope())
                {
                    MemorySegment bufferSegment = MemorySegment.allocateNative(buffer.remaining(), scope);
                    written = quiche_h.quiche_conn_send(quicheConn, bufferSegment.address(), buffer.remaining(), sendInfo.address());
                    buffer.put(bufferSegment.asByteBuffer().order(ByteOrder.nativeOrder()).slice().limit((int)written));
                    buffer.position(prevPosition);
                }
            }

            if (written == quiche_h.quiche_error.QUICHE_ERR_DONE)
                return 0;
            if (written < 0L)
                throw new IOException("failed to send packet; err=" + quiche_h.quiche_error.errToString(written));
            buffer.position((int)(prevPosition + written));
            return (int)written;
        }
    }

    @Override
    public boolean isConnectionClosed()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_is_closed(quicheConn) != C_FALSE;
        }
    }

    @Override
    public boolean isConnectionEstablished()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_is_established(quicheConn) != C_FALSE;
        }
    }

    @Override
    public long nextTimeout()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_timeout_as_millis(quicheConn);
        }
    }

    @Override
    public void onTimeout()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            quiche_h.quiche_conn_on_timeout(quicheConn);
        }
    }

    @Override
    public String getNegotiatedProtocol()
    {
        try (AutoLock ignore = lock.lock(); ResourceScope scope = ResourceScope.newConfinedScope())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            MemorySegment outSegment = MemorySegment.allocateNative(CLinker.C_POINTER, scope);
            MemorySegment outLenSegment = MemorySegment.allocateNative(CLinker.C_LONG, scope);
            quiche_h.quiche_conn_application_proto(quicheConn, outSegment.address(), outLenSegment.address());

            long outLen = outLenSegment.asByteBuffer().order(ByteOrder.nativeOrder()).getLong();
            if (outLen == 0L)
                return null;
            byte[] out = new byte[(int)outLen];
            // dereference outSegment pointer
            MemoryAddress memoryAddress = MemoryAddress.ofLong(outSegment.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
            memoryAddress.asSegment(outLen, ResourceScope.globalScope()).asByteBuffer().get(out);

            return new String(out, StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean close(long error, String reason)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("connection was released");
                return false;
            }

            int rc;
            if (reason == null)
            {
                rc = quiche_h.quiche_conn_close(quicheConn, C_TRUE, error, MemoryAddress.NULL, 0);
            }
            else
            {
                try (ResourceScope scope = ResourceScope.newConfinedScope())
                {
                    byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
                    MemorySegment reasonSegment = MemorySegment.allocateNative(reasonBytes.length, scope);
                    reasonSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(reasonBytes);
                    int length = reasonBytes.length;
                    MemoryAddress reasonAddress = reasonSegment.address();
                    rc = quiche_h.quiche_conn_close(quicheConn, C_TRUE, error, reasonAddress, length);
                }
            }

            if (rc == 0)
                return true;
            if (rc == quiche_h.quiche_error.QUICHE_ERR_DONE)
                return false;
            if (LOG.isDebugEnabled())
                LOG.debug("could not close connection: {}", quiche_h.quiche_error.errToString(rc));
            return false;
        }
    }

    @Override
    public void dispose()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn != null)
                quiche_h.quiche_conn_free(quicheConn);
            quicheConn = null;

            if (quicheConfig != null)
                quiche_h.quiche_config_free(quicheConfig);
            quicheConfig = null;

            if (scope != null)
                scope.close();
            scope = null;
            sendInfo = null;
            recvInfo = null;
            stats = null;
        }
    }

    @Override
    public boolean isDraining()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_is_draining(quicheConn) != C_FALSE;
        }
    }

    @Override
    public int maxLocalStreams()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            quiche_h.quiche_conn_stats(quicheConn, stats.address());
            return (int)quiche_stats.get_peer_initial_max_streams_bidi(stats);
        }
    }

    @Override
    public long windowCapacity()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            quiche_h.quiche_conn_stats(quicheConn, stats.address());
            return quiche_stats.get_cwnd(stats);
        }
    }

    @Override
    public long windowCapacity(long streamId) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");
            long value = quiche_h.quiche_conn_stream_capacity(quicheConn, streamId);
            if (value < 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not read window capacity for stream {} err={}", streamId, quiche_h.quiche_error.errToString(value));
            }
            return value;
        }
    }

    @Override
    public void shutdownStream(long streamId, boolean writeSide, long error) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");
            int direction = writeSide ? quiche_h.quiche_shutdown.QUICHE_SHUTDOWN_WRITE : quiche_h.quiche_shutdown.QUICHE_SHUTDOWN_READ;
            int rc = quiche_h.quiche_conn_stream_shutdown(quicheConn, streamId, direction, error);
            if (rc == 0 || rc == quiche_h.quiche_error.QUICHE_ERR_DONE)
                return;
            throw new IOException("failed to shutdown stream " + streamId + ": " + quiche_h.quiche_error.errToString(rc));
        }
    }

    @Override
    public int feedClearBytesForStream(long streamId, ByteBuffer buffer, boolean last) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");

            long written;
            if (buffer.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                MemorySegment bufferSegment = MemorySegment.ofByteBuffer(buffer);
                written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, bufferSegment.address(), buffer.remaining(), last ? C_TRUE : C_FALSE);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                try (ResourceScope scope = ResourceScope.newConfinedScope())
                {
                    if (buffer.remaining() == 0)
                    {
                        written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, MemoryAddress.NULL, 0, last ? C_TRUE : C_FALSE);
                    }
                    else
                    {
                        MemorySegment bufferSegment = MemorySegment.allocateNative(buffer.remaining(), scope);
                        int prevPosition = buffer.position();
                        bufferSegment.asByteBuffer().order(ByteOrder.nativeOrder()).put(buffer);
                        buffer.position(prevPosition);
                        written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, bufferSegment.address(), buffer.remaining(), last ? C_TRUE : C_FALSE);
                    }
                }
            }

            if (written == quiche_h.quiche_error.QUICHE_ERR_DONE)
                return 0;
            if (written < 0L)
                throw new IOException("failed to write to stream " + streamId + "; err=" + quiche_h.quiche_error.errToString(written));
            buffer.position((int)(buffer.position() + written));
            return (int)written;
        }
    }

    @Override
    public int drainClearBytesForStream(long streamId, ByteBuffer buffer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");

            long read;
            try (ResourceScope scope = ResourceScope.newConfinedScope())
            {
                if (buffer.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    MemorySegment bufferSegment = MemorySegment.ofByteBuffer(buffer);
                    MemorySegment fin = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
                    read = quiche_h.quiche_conn_stream_recv(quicheConn, streamId, bufferSegment.address(), buffer.remaining(), fin.address());
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    MemorySegment bufferSegment = MemorySegment.allocateNative(buffer.remaining(), scope);

                    MemorySegment fin = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
                    read = quiche_h.quiche_conn_stream_recv(quicheConn, streamId, bufferSegment.address(), buffer.remaining(), fin.address());

                    int prevPosition = buffer.position();
                    buffer.put(bufferSegment.asByteBuffer().order(ByteOrder.nativeOrder()).limit((int)read));
                    buffer.position(prevPosition);
                }
            }

            if (read == quiche_h.quiche_error.QUICHE_ERR_DONE)
                return isStreamFinished(streamId) ? -1 : 0;
            if (read < 0L)
                throw new IOException("failed to read from stream " + streamId + "; err=" + quiche_h.quiche_error.errToString(read));
            buffer.position((int)(buffer.position() + read));
            return (int)read;
        }
    }

    @Override
    public boolean isStreamFinished(long streamId)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_stream_finished(quicheConn, streamId) != C_FALSE;
        }
    }

    @Override
    public CloseInfo getRemoteCloseInfo()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            try (ResourceScope scope = ResourceScope.newConfinedScope())
            {
                MemorySegment app = MemorySegment.allocateNative(CLinker.C_CHAR, scope);
                MemorySegment error = MemorySegment.allocateNative(CLinker.C_LONG, scope);
                MemorySegment reason = MemorySegment.allocateNative(CLinker.C_POINTER, scope);
                MemorySegment reasonLength = MemorySegment.allocateNative(CLinker.C_LONG, scope);
                if (quiche_h.quiche_conn_peer_error(quicheConn, app.address(), error.address(), reason.address(), reasonLength.address()) != C_FALSE)
                {
                    long errorValue = error.asByteBuffer().order(ByteOrder.nativeOrder()).getLong();
                    long reasonLengthValue = reasonLength.asByteBuffer().order(ByteOrder.nativeOrder()).getLong();

                    String reasonValue;
                    if (reasonLengthValue == 0L)
                    {
                        reasonValue = null;
                    }
                    else
                    {
                        byte[] reasonBytes = new byte[(int)reasonLengthValue];
                        // dereference reason pointer
                        MemoryAddress memoryAddress = MemoryAddress.ofLong(reason.asByteBuffer().order(ByteOrder.nativeOrder()).getLong());
                        memoryAddress.asSegment(reasonLengthValue, ResourceScope.globalScope()).asByteBuffer().get(reasonBytes);
                        reasonValue = new String(reasonBytes, StandardCharsets.UTF_8);
                    }

                    return new CloseInfo(errorValue, reasonValue);
                }
                return null;
            }
        }
    }
}
