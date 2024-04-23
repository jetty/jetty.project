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

package org.eclipse.jetty.quic.quiche.foreign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.quic.quiche.Quiche;
import org.eclipse.jetty.quic.quiche.Quiche.quic_error;
import org.eclipse.jetty.quic.quiche.Quiche.quiche_error;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.quic.quiche.Quiche.QUICHE_MAX_CONN_ID_LEN;

public class ForeignQuicheConnection extends QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ForeignQuicheConnection.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Quiche does not allow concurrent calls with the same connection.
    private final AutoLock lock = new AutoLock();
    private MemorySegment quicheConn;
    private MemorySegment quicheConfig;
    private Arena scope;
    private MemorySegment sendInfo;
    private MemorySegment recvInfo;
    private MemorySegment transportParams;
    private MemorySegment pathStats;

    private ForeignQuicheConnection(MemorySegment quicheConn, MemorySegment quicheConfig, Arena scope)
    {
        this.quicheConn = quicheConn;
        this.quicheConfig = quicheConfig;
        this.scope = scope;
        this.sendInfo = quiche_send_info.allocate(scope);
        this.recvInfo = quiche_recv_info.allocate(scope);
        this.transportParams = quiche_transport_params.allocate(scope);
        this.pathStats = quiche_path_stats.allocate(scope);
    }

    public static byte[] fromPacket(ByteBuffer packet)
    {
        try (Arena scope = Arena.ofConfined())
        {
            MemorySegment type = scope.allocate(NativeHelper.C_CHAR);
            MemorySegment version = scope.allocate(NativeHelper.C_INT);

            // Source Connection ID
            MemorySegment scid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment scid_len = scope.allocate(NativeHelper.C_LONG);
            scid_len.set(NativeHelper.C_LONG, 0L, scid.byteSize());

            // Destination Connection ID
            MemorySegment dcid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment dcid_len = scope.allocate(NativeHelper.C_LONG);
            dcid_len.set(NativeHelper.C_LONG, 0L, dcid.byteSize());

            MemorySegment token = scope.allocate(QuicheConnection.TokenMinter.MAX_TOKEN_LENGTH);
            MemorySegment token_len = scope.allocate(NativeHelper.C_LONG);
            token_len.set(NativeHelper.C_LONG, 0L, token.byteSize());

            if (LOG.isDebugEnabled())
                LOG.debug("getting header info (fromPacket)...");
            int rc;

            if (packet.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                MemorySegment packetReadSegment = MemorySegment.ofBuffer(packet);
                rc = quiche_h.quiche_header_info(packetReadSegment, packet.remaining(), QUICHE_MAX_CONN_ID_LEN,
                    version, type,
                    scid, scid_len,
                    dcid, dcid_len,
                    token, token_len);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                MemorySegment packetReadSegment = scope.allocate(packet.remaining());
                int prevPosition = packet.position();
                packetReadSegment.asByteBuffer().put(packet);
                packet.position(prevPosition);
                rc = quiche_h.quiche_header_info(packetReadSegment, packet.remaining(), QUICHE_MAX_CONN_ID_LEN,
                    version, type,
                    scid, scid_len,
                    dcid, dcid_len,
                    token, token_len);
            }
            if (rc < 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("quiche cannot read header info from packet {}", BufferUtil.toDetailString(packet));
                return null;
            }

            byte[] bytes = new byte[(int)dcid_len.get(NativeHelper.C_LONG, 0L)];
            dcid.asByteBuffer().get(bytes);
            return bytes;
        }
    }

    public static ForeignQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer) throws IOException
    {
        return connect(quicheConfig, local, peer, QUICHE_MAX_CONN_ID_LEN);
    }

    public static ForeignQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        if (connectionIdLength > QUICHE_MAX_CONN_ID_LEN)
            throw new IOException("Connection ID length is too large: " + connectionIdLength + " > " + QUICHE_MAX_CONN_ID_LEN);


        Arena arena = Arena.ofShared();
        boolean keepScope = false;
        try
        {
            byte[] scidBytes = new byte[connectionIdLength];
            SECURE_RANDOM.nextBytes(scidBytes);
            MemorySegment scid = arena.allocate(scidBytes.length);
            scid.asByteBuffer().put(scidBytes);
            MemorySegment libQuicheConfig = buildConfig(quicheConfig, arena);

            MemorySegment localSockaddr = sockaddr.convert(local, arena);
            MemorySegment peerSockaddr = sockaddr.convert(peer, arena);
            
            MemorySegment quicheConn = quiche_h.quiche_connect(arena.allocateFrom(peer.getHostString()), scid, scid.byteSize(), localSockaddr, (int)localSockaddr.byteSize(), peerSockaddr, (int)peerSockaddr.byteSize(), libQuicheConfig);
            ForeignQuicheConnection connection = new ForeignQuicheConnection(quicheConn, libQuicheConfig, arena);
            keepScope = true;
            return connection;
        }
        finally
        {
            if (!keepScope)
                arena.close();
        }
    }

    private static MemorySegment buildConfig(QuicheConfig config, SegmentAllocator allocator) throws IOException
    {
        MemorySegment quicheConfig = quiche_h.quiche_config_new(config.getVersion());
        if (quicheConfig == null)
            throw new IOException("Failed to create quiche config");

        Boolean verifyPeer = config.getVerifyPeer();
        if (verifyPeer != null)
            quiche_h.quiche_config_verify_peer(quicheConfig, verifyPeer);

        String trustedCertsPemPath = config.getTrustedCertsPemPath();
        if (trustedCertsPemPath != null)
        {
            int rc = quiche_h.quiche_config_load_verify_locations_from_file(quicheConfig, allocator.allocateFrom(trustedCertsPemPath));
            if (rc < 0)
                throw new IOException("Error loading trusted certificates file " + trustedCertsPemPath + " : " + Quiche.quiche_error.errToString(rc));
        }

        String certChainPemPath = config.getCertChainPemPath();
        if (certChainPemPath != null)
        {

            int rc = quiche_h.quiche_config_load_cert_chain_from_pem_file(quicheConfig, allocator.allocateFrom(certChainPemPath));
            if (rc < 0)
                throw new IOException("Error loading certificate chain file " + certChainPemPath + " : " + Quiche.quiche_error.errToString(rc));
        }

        String privKeyPemPath = config.getPrivKeyPemPath();
        if (privKeyPemPath != null)
        {
            int rc = quiche_h.quiche_config_load_priv_key_from_pem_file(quicheConfig, allocator.allocateFrom(privKeyPemPath));
            if (rc < 0)
                throw new IOException("Error loading private key file " + privKeyPemPath + " : " + Quiche.quiche_error.errToString(rc));
        }

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
            MemorySegment segment = allocator.allocate(bytes.length);
            segment.asByteBuffer().put(bytes);
            quiche_h.quiche_config_set_application_protos(quicheConfig, segment, segment.byteSize());
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
            quiche_h.quiche_config_set_disable_active_migration(quicheConfig, disableActiveMigration);

        Long maxConnectionWindow = config.getMaxConnectionWindow();
        if (maxConnectionWindow != null)
            quiche_h.quiche_config_set_max_connection_window(quicheConfig, maxConnectionWindow);

        Long maxStreamWindow = config.getMaxStreamWindow();
        if (maxStreamWindow != null)
            quiche_h.quiche_config_set_max_stream_window(quicheConfig, maxStreamWindow);

        Long activeConnectionIdLimit = config.getActiveConnectionIdLimit();
        if (activeConnectionIdLimit != null)
            quiche_h.quiche_config_set_active_connection_id_limit(quicheConfig, activeConnectionIdLimit);

        return quicheConfig;
    }

    public static boolean negotiate(TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        try (Arena scope = Arena.ofConfined())
        {
            MemorySegment packetReadSegment;
            if (packetRead.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                packetReadSegment = MemorySegment.ofBuffer(packetRead);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                packetReadSegment = scope.allocate(packetRead.remaining());
                int prevPosition = packetRead.position();
                packetReadSegment.asByteBuffer().put(packetRead);
                packetRead.position(prevPosition);
            }

            MemorySegment type = scope.allocate(NativeHelper.C_CHAR);
            MemorySegment version = scope.allocate(NativeHelper.C_INT);

            // Source Connection ID
            MemorySegment scid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment scid_len = scope.allocate(NativeHelper.C_LONG);
            scid_len.set(NativeHelper.C_LONG, 0L, scid.byteSize());

            // Destination Connection ID
            MemorySegment dcid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment dcid_len = scope.allocate(NativeHelper.C_LONG);
            dcid_len.set(NativeHelper.C_LONG, 0L, dcid.byteSize());

            MemorySegment token = scope.allocate(TokenMinter.MAX_TOKEN_LENGTH);
            MemorySegment token_len = scope.allocate(NativeHelper.C_LONG);
            token_len.set(NativeHelper.C_LONG, 0L, token.byteSize());

            if (LOG.isDebugEnabled())
                LOG.debug("getting header info (negotiate)...");
            int rc = quiche_h.quiche_header_info(packetReadSegment, packetRead.remaining(), QUICHE_MAX_CONN_ID_LEN,
                version, type,
                scid, scid_len,
                dcid, dcid_len,
                token, token_len);
            if (rc < 0)
                throw new IOException("failed to parse header: " + quiche_error.errToString(rc));
            packetRead.position(packetRead.limit());

            if (LOG.isDebugEnabled())
            {
                LOG.debug("version: {}", version.get(NativeHelper.C_INT, 0L));
                LOG.debug("type: {}", type.get(NativeHelper.C_CHAR, 0L));
                LOG.debug("scid len: {}", scid_len.get(NativeHelper.C_LONG, 0L));
                LOG.debug("dcid len: {}", dcid_len.get(NativeHelper.C_LONG, 0L));
                LOG.debug("token len: {}", token_len.get(NativeHelper.C_LONG, 0L));
            }

            if (!quiche_h.quiche_version_is_supported(version.get(NativeHelper.C_INT, 0L)))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("version negotiation");

                MemorySegment packetToSendSegment;
                if (packetToSend.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    packetToSendSegment = MemorySegment.ofBuffer(packetToSend);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    packetToSendSegment = scope.allocate(packetToSend.remaining());
                }

                long generated = quiche_h.quiche_negotiate_version(scid, scid_len.get(NativeHelper.C_LONG, 0L), dcid, dcid_len.get(NativeHelper.C_LONG, 0L), packetToSendSegment, packetToSend.remaining());
                if (generated < 0)
                    throw new IOException("failed to create vneg packet : " + quiche_error.errToString(generated));
                if (!packetToSend.isDirect())
                    packetToSend.put(packetToSendSegment.asByteBuffer().limit((int)generated));
                else
                    packetToSend.position((int)(packetToSend.position() + generated));
                return true;
            }

            if (token_len.get(NativeHelper.C_LONG, 0L) == 0L)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stateless retry");

                byte[] dcidBytes = new byte[(int)dcid_len.get(NativeHelper.C_LONG, 0L)];
                dcid.asByteBuffer().get(dcidBytes);
                byte[] tokenBytes = tokenMinter.mint(dcidBytes, dcidBytes.length);
                token.asByteBuffer().put(tokenBytes);

                byte[] newCid = new byte[QUICHE_MAX_CONN_ID_LEN];
                SECURE_RANDOM.nextBytes(newCid);
                MemorySegment newCidSegment = scope.allocate(newCid.length);
                newCidSegment.asByteBuffer().put(newCid);

                MemorySegment packetToSendSegment;
                if (packetToSend.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    packetToSendSegment = MemorySegment.ofBuffer(packetToSend);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    packetToSendSegment = scope.allocate(packetToSend.remaining());
                }

                long generated = quiche_h.quiche_retry(scid, scid_len.get(NativeHelper.C_LONG, 0L),
                    dcid, dcid_len.get(NativeHelper.C_LONG, 0L),
                    newCidSegment, newCid.length,
                    token, tokenBytes.length,
                    version.get(NativeHelper.C_INT, 0L),
                    packetToSendSegment, packetToSendSegment.byteSize()
                );
                if (generated < 0)
                    throw new IOException("failed to create retry packet: " + quiche_error.errToString(generated));
                if (!packetToSend.isDirect())
                    packetToSend.put(packetToSendSegment.asByteBuffer().limit((int)generated));
                else
                    packetToSend.position((int)(packetToSend.position() + generated));
                return true;
            }

            return false;
        }
    }

    public static ForeignQuicheConnection tryAccept(QuicheConfig quicheConfig, TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress local, SocketAddress peer) throws IOException
    {
        boolean keepScope = false;
        Arena scope = Arena.ofShared();
        try
        {
            MemorySegment type = scope.allocate(NativeHelper.C_CHAR);
            MemorySegment version = scope.allocate(NativeHelper.C_INT);

            // Source Connection ID
            MemorySegment scid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment scid_len = scope.allocate(NativeHelper.C_LONG);
            scid_len.set(NativeHelper.C_LONG, 0L, scid.byteSize());

            // Destination Connection ID
            MemorySegment dcid = scope.allocate(QUICHE_MAX_CONN_ID_LEN);
            MemorySegment dcid_len = scope.allocate(NativeHelper.C_LONG);
            dcid_len.set(NativeHelper.C_LONG, 0L, dcid.byteSize());

            MemorySegment token = scope.allocate(TokenMinter.MAX_TOKEN_LENGTH);
            MemorySegment token_len = scope.allocate(NativeHelper.C_LONG);
            token_len.set(NativeHelper.C_LONG, 0L, token.byteSize());

            if (LOG.isDebugEnabled())
                LOG.debug("getting header info (tryAccept)...");
            int rc;

            if (packetRead.isDirect())
            {
                // If the ByteBuffer is direct, it can be used without any copy.
                MemorySegment packetReadSegment = MemorySegment.ofBuffer(packetRead);
                rc = quiche_h.quiche_header_info(packetReadSegment, packetRead.remaining(), QUICHE_MAX_CONN_ID_LEN,
                    version, type,
                    scid, scid_len,
                    dcid, dcid_len,
                    token, token_len);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                try (Arena segmentScope = Arena.ofConfined())
                {
                    MemorySegment packetReadSegment = segmentScope.allocate(packetRead.remaining());
                    int prevPosition = packetRead.position();
                    packetReadSegment.asByteBuffer().put(packetRead);
                    packetRead.position(prevPosition);
                    rc = quiche_h.quiche_header_info(packetReadSegment, packetRead.remaining(), QUICHE_MAX_CONN_ID_LEN,
                        version, type,
                        scid, scid_len,
                        dcid, dcid_len,
                        token, token_len);
                }
            }
            if (rc < 0)
                throw new IOException("failed to parse header: " + quiche_error.errToString(rc));

            if (LOG.isDebugEnabled())
            {
                LOG.debug("version: {}", version.get(NativeHelper.C_INT, 0L));
                LOG.debug("type: {}", type.get(NativeHelper.C_CHAR, 0L));
                LOG.debug("scid len: {}", scid_len.get(NativeHelper.C_LONG, 0L));
                LOG.debug("dcid len: {}", dcid_len.get(NativeHelper.C_LONG, 0L));
                LOG.debug("token len: {}", token_len.get(NativeHelper.C_LONG, 0L));
            }

            if (!quiche_h.quiche_version_is_supported(version.get(NativeHelper.C_INT, 0L)))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("need version negotiation");
                return null;
            }

            int tokenLen = (int)token_len.get(NativeHelper.C_LONG, 0L);
            if (tokenLen == 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("need stateless retry");
                return null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("token validation...");
            // Original Destination Connection ID
            byte[] tokenBytes = new byte[(int)token.byteSize()];
            token.asByteBuffer().get(tokenBytes, 0, tokenLen);
            byte[] odcidBytes = tokenValidator.validate(tokenBytes, tokenLen);
            if (odcidBytes == null)
                throw new TokenValidationException("invalid address validation token");
            if (LOG.isDebugEnabled())
                LOG.debug("validated token");
            MemorySegment odcid = scope.allocate(odcidBytes.length);
            odcid.asByteBuffer().put(odcidBytes);

            if (LOG.isDebugEnabled())
                LOG.debug("connection creation...");
            MemorySegment libQuicheConfig = buildConfig(quicheConfig, scope);

            MemorySegment localSockaddr = sockaddr.convert(local, scope);
            MemorySegment peerSockaddr = sockaddr.convert(peer, scope);
            MemorySegment quicheConn = quiche_h.quiche_accept(dcid, dcid_len.get(NativeHelper.C_LONG, 0L), odcid, odcid.byteSize(), localSockaddr, (int)localSockaddr.byteSize(), peerSockaddr, (int)peerSockaddr.byteSize(), libQuicheConfig);
            if (quicheConn == null)
            {
                quiche_h.quiche_config_free(libQuicheConfig);
                throw new IOException("failed to create connection");
            }

            if (LOG.isDebugEnabled())
                LOG.debug("connection created");
            ForeignQuicheConnection quicheConnection = new ForeignQuicheConnection(quicheConn, libQuicheConfig, scope);
            if (LOG.isDebugEnabled())
                LOG.debug("accepted, immediately receiving the same packet - remaining in buffer: {}", packetRead.remaining());
            while (packetRead.hasRemaining())
            {
                quicheConnection.feedCipherBytes(packetRead, local, peer);
            }
            keepScope = true;
            return quicheConnection;
        }
        finally
        {
            if (!keepScope)
                scope.close();
        }
    }

    public byte[] getPeerCertificate()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            try (Arena scope = Arena.ofConfined())
            {
                MemorySegment outSegment = scope.allocate(NativeHelper.C_POINTER);
                MemorySegment outLenSegment = scope.allocate(NativeHelper.C_LONG);
                quiche_h.quiche_conn_peer_cert(quicheConn, outSegment, outLenSegment);

                long outLen = outLenSegment.get(NativeHelper.C_LONG, 0L);
                if (outLen == 0L)
                    return null;
                byte[] out = new byte[(int)outLen];
                // dereference outSegment pointer
                outSegment.get(NativeHelper.C_POINTER, 0L).reinterpret(outLen).asByteBuffer().get(out);
                return out;
            }
        }
    }

    @Override
    protected List<Long> iterableStreamIds(boolean write)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            MemorySegment quiche_stream_iter;
            if (write)
                quiche_stream_iter = quiche_h.quiche_conn_writable(quicheConn);
            else
                quiche_stream_iter = quiche_h.quiche_conn_readable(quicheConn);

            List<Long> result = new ArrayList<>();
            try (Arena scope = Arena.ofConfined())
            {
                MemorySegment streamIdSegment = scope.allocate(NativeHelper.C_LONG);
                while (quiche_h.quiche_stream_iter_next(quiche_stream_iter, streamIdSegment))
                {
                    long streamId = streamIdSegment.get(NativeHelper.C_LONG, 0L);
                    result.add(streamId);
                }
            }

            quiche_h.quiche_stream_iter_free(quiche_stream_iter);
            return result;
        }
    }

    @Override
    public int feedCipherBytes(ByteBuffer buffer, SocketAddress local, SocketAddress peer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("Cannot receive when not connected");

            long received;
            try (Arena scope = Arena.ofConfined())
            {
                quiche_recv_info.setSocketAddress(recvInfo, local, peer, scope);
                if (buffer.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    MemorySegment bufferSegment = MemorySegment.ofBuffer(buffer);
                    received = quiche_h.quiche_conn_recv(quicheConn, bufferSegment, buffer.remaining(), recvInfo);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                    MemorySegment bufferSegment = scope.allocate(buffer.remaining());
                    int prevPosition = buffer.position();
                    bufferSegment.asByteBuffer().put(buffer);
                    buffer.position(prevPosition);
                    received = quiche_h.quiche_conn_recv(quicheConn, bufferSegment, buffer.remaining(), recvInfo);
                }
            }
            // If quiche_conn_recv() fails, quiche_conn_local_error() can be called to get the standard error.
            if (received < 0)
                throw new IOException("failed to receive packet;" +
                    " quiche_err=" + quiche_error.errToString(received) +
                    " quic_err=" + quic_error.errToString(getLocalCloseInfo().error()));
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
                MemorySegment bufferSegment = MemorySegment.ofBuffer(buffer);
                written = quiche_h.quiche_conn_send(quicheConn, bufferSegment, buffer.remaining(), sendInfo);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                try (Arena scope = Arena.ofConfined())
                {
                    MemorySegment bufferSegment = scope.allocate(buffer.remaining());
                    written = quiche_h.quiche_conn_send(quicheConn, bufferSegment, buffer.remaining(), sendInfo);
                    buffer.put(bufferSegment.asByteBuffer().slice().limit((int)written));
                    buffer.position(prevPosition);
                }
            }

            if (written == quiche_error.QUICHE_ERR_DONE)
                return 0;
            if (written < 0L)
                throw new IOException("failed to send packet; quiche_err=" + quiche_error.errToString(written));
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
            return quiche_h.quiche_conn_is_closed(quicheConn) != false;
        }
    }

    @Override
    public boolean isConnectionEstablished()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_is_established(quicheConn) != false;
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
        try (AutoLock ignore = lock.lock(); Arena scope = Arena.ofConfined())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            MemorySegment outSegment = scope.allocate(NativeHelper.C_POINTER);
            MemorySegment outLenSegment = scope.allocate(NativeHelper.C_LONG);
            quiche_h.quiche_conn_application_proto(quicheConn, outSegment, outLenSegment);

            long outLen = outLenSegment.get(NativeHelper.C_LONG, 0L);
            if (outLen == 0L)
                return null;
            byte[] out = new byte[(int)outLen];
            // dereference outSegment pointer
            outSegment.get(NativeHelper.C_POINTER, 0L).reinterpret(outLen).asByteBuffer().get(out);
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
                rc = quiche_h.quiche_conn_close(quicheConn, true, error, MemorySegment.NULL, 0);
            }
            else
            {
                try (Arena scope = Arena.ofConfined())
                {
                    byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
                    MemorySegment reasonSegment = scope.allocate(reasonBytes.length);
                    reasonSegment.asByteBuffer().put(reasonBytes);
                    int length = reasonBytes.length;
                    rc = quiche_h.quiche_conn_close(quicheConn, true, error, reasonSegment, length);
                }
            }

            if (rc == 0)
                return true;
            if (rc == quiche_error.QUICHE_ERR_DONE)
                return false;
            if (LOG.isDebugEnabled())
                LOG.debug("could not close connection: {}", quiche_error.errToString(rc));
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
            transportParams = null;
            pathStats = null;
        }
    }

    @Override
    public boolean isDraining()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return quiche_h.quiche_conn_is_draining(quicheConn);
        }
    }

    @Override
    public int maxLocalStreams()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            quiche_h.quiche_conn_peer_transport_params(quicheConn, transportParams);
            return (int)quiche_transport_params.get_peer_initial_max_streams_bidi(transportParams);
        }
    }

    @Override
    public long windowCapacity()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            quiche_h.quiche_conn_path_stats(quicheConn, 0L, pathStats);
            return quiche_path_stats.get_cwnd(pathStats);
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
                    LOG.debug("could not read window capacity for stream {} quiche_err={}", streamId, quiche_error.errToString(value));
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
            if (rc == 0 || rc == quiche_error.QUICHE_ERR_DONE)
                return;
            throw new IOException("failed to shutdown stream " + streamId + ": " + quiche_error.errToString(rc));
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
                MemorySegment bufferSegment = MemorySegment.ofBuffer(buffer);
                written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, bufferSegment, buffer.remaining(), last);
            }
            else
            {
                // If the ByteBuffer is heap-allocated, it must be copied to native memory.
                try (Arena scope = Arena.ofConfined())
                {
                    if (buffer.remaining() == 0)
                    {
                        written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, MemorySegment.NULL, 0, last);
                    }
                    else
                    {
                        MemorySegment bufferSegment = scope.allocate(buffer.remaining());
                        int prevPosition = buffer.position();
                        bufferSegment.asByteBuffer().put(buffer);
                        buffer.position(prevPosition);
                        written = quiche_h.quiche_conn_stream_send(quicheConn, streamId, bufferSegment, buffer.remaining(), last);
                    }
                }
            }

            if (written == quiche_error.QUICHE_ERR_DONE)
            {
                int rc = quiche_h.quiche_conn_stream_writable(quicheConn, streamId, buffer.remaining());
                if (rc < 0)
                    throw new IOException("failed to write to stream " + streamId + "; quiche_err=" + quiche_error.errToString(rc));
                return 0;
            }
            if (written < 0L)
                throw new IOException("failed to write to stream " + streamId + "; quiche_err=" + quiche_error.errToString(written));
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
            try (Arena scope = Arena.ofConfined())
            {
                if (buffer.isDirect())
                {
                    // If the ByteBuffer is direct, it can be used without any copy.
                    MemorySegment bufferSegment = MemorySegment.ofBuffer(buffer);
                    MemorySegment fin = scope.allocate(NativeHelper.C_CHAR);
                    read = quiche_h.quiche_conn_stream_recv(quicheConn, streamId, bufferSegment, buffer.remaining(), fin);
                }
                else
                {
                    // If the ByteBuffer is heap-allocated, native memory must be copied to it.
                    MemorySegment bufferSegment = scope.allocate(buffer.remaining());

                    MemorySegment fin = scope.allocate(NativeHelper.C_CHAR);
                    read = quiche_h.quiche_conn_stream_recv(quicheConn, streamId, bufferSegment, buffer.remaining(), fin);

                    int prevPosition = buffer.position();
                    buffer.put(bufferSegment.asByteBuffer().limit((int)read));
                    buffer.position(prevPosition);
                }
            }

            if (read == quiche_error.QUICHE_ERR_DONE)
                return isStreamFinished(streamId) ? -1 : 0;
            if (read < 0L)
                throw new IOException("failed to read from stream " + streamId + "; quiche_err=" + quiche_error.errToString(read));
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
            return quiche_h.quiche_conn_stream_finished(quicheConn, streamId);
        }
    }

    @Override
    public CloseInfo getRemoteCloseInfo()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            try (Arena scope = Arena.ofConfined())
            {
                MemorySegment app = scope.allocate(NativeHelper.C_CHAR);
                MemorySegment error = scope.allocate(NativeHelper.C_LONG);
                MemorySegment reason = scope.allocate(NativeHelper.C_POINTER);
                MemorySegment reasonLength = scope.allocate(NativeHelper.C_LONG);
                if (quiche_h.quiche_conn_peer_error(quicheConn, app, error, reason, reasonLength))
                {
                    long errorValue = error.get(NativeHelper.C_LONG, 0L);
                    long reasonLengthValue = reasonLength.get(NativeHelper.C_LONG, 0L);

                    String reasonValue;
                    if (reasonLengthValue == 0L)
                    {
                        reasonValue = null;
                    }
                    else
                    {
                        byte[] reasonBytes = new byte[(int)reasonLengthValue];
                        // dereference reason pointer
                        reason.get(NativeHelper.C_POINTER, 0L).reinterpret(reasonLengthValue).asByteBuffer().get(reasonBytes);
                        reasonValue = new String(reasonBytes, StandardCharsets.UTF_8);
                    }

                    return new CloseInfo(errorValue, reasonValue);
                }
                return null;
            }
        }
    }

    @Override
    public CloseInfo getLocalCloseInfo()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            try (Arena scope = Arena.ofConfined())
            {
                MemorySegment app = scope.allocate(NativeHelper.C_CHAR);
                MemorySegment error = scope.allocate(NativeHelper.C_LONG);
                MemorySegment reason = scope.allocate(NativeHelper.C_POINTER);
                MemorySegment reasonLength = scope.allocate(NativeHelper.C_LONG);
                if (quiche_h.quiche_conn_local_error(quicheConn, app, error, reason, reasonLength))
                {
                    long errorValue = error.get(NativeHelper.C_LONG, 0L);
                    long reasonLengthValue = reasonLength.get(NativeHelper.C_LONG, 0L);

                    String reasonValue;
                    if (reasonLengthValue == 0L)
                    {
                        reasonValue = null;
                    }
                    else
                    {
                        byte[] reasonBytes = new byte[(int)reasonLengthValue];
                        // dereference reason pointer
                        reason.get(NativeHelper.C_POINTER, 0L).reinterpret(reasonLengthValue).asByteBuffer().get(reasonBytes);
                        reasonValue = new String(reasonBytes, StandardCharsets.UTF_8);
                    }

                    return new CloseInfo(errorValue, reasonValue);
                }
                return null;
            }
        }
    }
}
