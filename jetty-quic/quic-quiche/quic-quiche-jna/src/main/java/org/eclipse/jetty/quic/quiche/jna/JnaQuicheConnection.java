//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.quic.quiche.Quiche.quiche_error;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.quic.quiche.Quiche.QUICHE_MAX_CONN_ID_LEN;

public class JnaQuicheConnection extends QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(JnaQuicheConnection.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static
    {
        LibQuiche.Logging.enable();
    }

    // Quiche does not allow concurrent calls with the same connection.
    private final AutoLock lock = new AutoLock();
    private LibQuiche.quiche_conn quicheConn;
    private LibQuiche.quiche_config quicheConfig;

    private JnaQuicheConnection(LibQuiche.quiche_conn quicheConn, LibQuiche.quiche_config quicheConfig)
    {
        this.quicheConn = quicheConn;
        this.quicheConfig = quicheConfig;
    }

    public static byte[] fromPacket(ByteBuffer packet)
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scidLen = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcidLen = new size_t_pointer(dcid.length);

        byte[] token = new byte[QuicheConnection.TokenMinter.MAX_TOKEN_LENGTH];
        size_t_pointer tokenLen = new size_t_pointer(token.length);

        LOG.debug("getting header info (fromPacket)...");
        int rc = LibQuiche.INSTANCE.quiche_header_info(packet, new size_t(packet.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scidLen,
            dcid, dcidLen,
            token, tokenLen);
        if (rc < 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("quiche cannot read header info from packet {}", BufferUtil.toDetailString(packet));
            return null;
        }
        return resizeIfNeeded(dcid, (int)dcidLen.getValue());
    }

    private static byte[] resizeIfNeeded(byte[] buffer, int length)
    {
        byte[] sizedBuffer;
        if (length == buffer.length)
        {
            sizedBuffer = buffer;
        }
        else
        {
            sizedBuffer = new byte[length];
            System.arraycopy(buffer, 0, sizedBuffer, 0, sizedBuffer.length);
        }
        return sizedBuffer;
    }

    public static JnaQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer) throws IOException
    {
        return connect(quicheConfig, local, peer, QUICHE_MAX_CONN_ID_LEN);
    }

    public static JnaQuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        if (connectionIdLength > QUICHE_MAX_CONN_ID_LEN)
            throw new IOException("Connection ID length is too large: " + connectionIdLength + " > " + QUICHE_MAX_CONN_ID_LEN);
        byte[] scid = new byte[connectionIdLength];
        SECURE_RANDOM.nextBytes(scid);
        LibQuiche.quiche_config libQuicheConfig = buildConfig(quicheConfig);

        SizedStructure<sockaddr> localSockaddr = sockaddr.convert(local);
        SizedStructure<sockaddr> peerSockaddr = sockaddr.convert(peer);
        LibQuiche.quiche_conn quicheConn = LibQuiche.INSTANCE.quiche_connect(peer.getHostName(), scid, new size_t(scid.length), localSockaddr.getStructure(), localSockaddr.getSize(), peerSockaddr.getStructure(), peerSockaddr.getSize(), libQuicheConfig);
        return new JnaQuicheConnection(quicheConn, libQuicheConfig);
    }

    private static LibQuiche.quiche_config buildConfig(QuicheConfig config) throws IOException
    {
        LibQuiche.quiche_config quicheConfig = LibQuiche.INSTANCE.quiche_config_new(new uint32_t(config.getVersion()));
        if (quicheConfig == null)
            throw new IOException("Failed to create quiche config");

        Boolean verifyPeer = config.getVerifyPeer();
        if (verifyPeer != null)
            LibQuiche.INSTANCE.quiche_config_verify_peer(quicheConfig, verifyPeer);

        String certChainPemPath = config.getCertChainPemPath();
        if (certChainPemPath != null)
            LibQuiche.INSTANCE.quiche_config_load_cert_chain_from_pem_file(quicheConfig, certChainPemPath);

        String privKeyPemPath = config.getPrivKeyPemPath();
        if (privKeyPemPath != null)
            LibQuiche.INSTANCE.quiche_config_load_priv_key_from_pem_file(quicheConfig, privKeyPemPath);

        String[] applicationProtos = config.getApplicationProtos();
        if (applicationProtos != null)
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (String proto : applicationProtos)
            {
                byte[] bytes = proto.getBytes(LibQuiche.CHARSET);
                baos.write(bytes.length);
                baos.write(bytes);
            }
            byte[] bytes = baos.toByteArray();
            LibQuiche.INSTANCE.quiche_config_set_application_protos(quicheConfig, bytes, new size_t(bytes.length));
        }

        QuicheConfig.CongestionControl cc = config.getCongestionControl();
        if (cc != null)
            LibQuiche.INSTANCE.quiche_config_set_cc_algorithm(quicheConfig, cc.getValue());

        Long maxIdleTimeout = config.getMaxIdleTimeout();
        if (maxIdleTimeout != null)
            LibQuiche.INSTANCE.quiche_config_set_max_idle_timeout(quicheConfig, new uint64_t(maxIdleTimeout));

        Long initialMaxData = config.getInitialMaxData();
        if (initialMaxData != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_data(quicheConfig, new uint64_t(initialMaxData));

        Long initialMaxStreamDataBidiLocal = config.getInitialMaxStreamDataBidiLocal();
        if (initialMaxStreamDataBidiLocal != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_stream_data_bidi_local(quicheConfig, new uint64_t(initialMaxStreamDataBidiLocal));

        Long initialMaxStreamDataBidiRemote = config.getInitialMaxStreamDataBidiRemote();
        if (initialMaxStreamDataBidiRemote != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_stream_data_bidi_remote(quicheConfig, new uint64_t(initialMaxStreamDataBidiRemote));

        Long initialMaxStreamDataUni = config.getInitialMaxStreamDataUni();
        if (initialMaxStreamDataUni != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_stream_data_uni(quicheConfig, new uint64_t(initialMaxStreamDataUni));

        Long initialMaxStreamsBidi = config.getInitialMaxStreamsBidi();
        if (initialMaxStreamsBidi != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_streams_bidi(quicheConfig, new uint64_t(initialMaxStreamsBidi));

        Long initialMaxStreamsUni = config.getInitialMaxStreamsUni();
        if (initialMaxStreamsUni != null)
            LibQuiche.INSTANCE.quiche_config_set_initial_max_streams_uni(quicheConfig, new uint64_t(initialMaxStreamsUni));

        Boolean disableActiveMigration = config.getDisableActiveMigration();
        if (disableActiveMigration != null)
            LibQuiche.INSTANCE.quiche_config_set_disable_active_migration(quicheConfig, disableActiveMigration);

        Long maxConnectionWindow = config.getMaxConnectionWindow();
        if (maxConnectionWindow != null)
            LibQuiche.INSTANCE.quiche_config_set_max_connection_window(quicheConfig, new uint64_t(maxConnectionWindow));

        Long maxStreamWindow = config.getMaxStreamWindow();
        if (maxStreamWindow != null)
            LibQuiche.INSTANCE.quiche_config_set_max_stream_window(quicheConfig, new uint64_t(maxStreamWindow));

        Long activeConnectionIdLimit = config.getActiveConnectionIdLimit();
        if (activeConnectionIdLimit != null)
            LibQuiche.INSTANCE.quiche_config_set_active_connection_id_limit(quicheConfig, new uint64_t(activeConnectionIdLimit));

        return quicheConfig;
    }

    public static String packetTypeAsString(ByteBuffer packet)
    {
        byte type = packetType(packet);
        return LibQuiche.packet_type.typeToString(type);
    }

    public static byte packetType(ByteBuffer packet)
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scid_len = new size_t_pointer(scid.length);

        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcid_len = new size_t_pointer(dcid.length);

        byte[] token = new byte[TokenMinter.MAX_TOKEN_LENGTH];
        size_t_pointer token_len = new size_t_pointer(token.length);

        LOG.debug("getting header info (packetType)...");
        int rc = LibQuiche.INSTANCE.quiche_header_info(packet, new size_t(packet.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scid_len,
            dcid, dcid_len,
            token, token_len);
        if (rc < 0)
            return (byte)rc;

        return type.getValue();
    }

    /**
     * Fully consumes the {@code packetRead} buffer.
     * @return true if a negotiation packet was written to the {@code packetToSend} buffer, false if negotiation failed
     * and the {@code packetRead} buffer can be dropped.
     */
    public static boolean negotiate(TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scid_len = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcid_len = new size_t_pointer(dcid.length);

        byte[] token = new byte[TokenMinter.MAX_TOKEN_LENGTH];
        size_t_pointer token_len = new size_t_pointer(token.length);

        LOG.debug("getting header info (negotiate)...");
        int rc = LibQuiche.INSTANCE.quiche_header_info(packetRead, new size_t(packetRead.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scid_len,
            dcid, dcid_len,
            token, token_len);
        if (rc < 0)
            throw new IOException("failed to parse header: " + quiche_error.errToString(rc));
        packetRead.position(packetRead.limit());

        LOG.debug("version: {}", version);
        LOG.debug("type: {}", type);
        LOG.debug("scid len: {}", scid_len);
        LOG.debug("dcid len: {}", dcid_len);
        LOG.debug("token len: {}", token_len);

        if (!LibQuiche.INSTANCE.quiche_version_is_supported(version.getPointee()))
        {
            LOG.debug("version negotiation");

            ssize_t generated = LibQuiche.INSTANCE.quiche_negotiate_version(scid, scid_len.getPointee(), dcid, dcid_len.getPointee(), packetToSend, new size_t(packetToSend.remaining()));
            packetToSend.position(packetToSend.position() + generated.intValue());
            if (generated.intValue() < 0)
                throw new IOException("failed to create vneg packet : " + quiche_error.errToString(generated.intValue()));
            return true;
        }

        if (token_len.getValue() == 0)
        {
            LOG.debug("stateless retry");

            token = tokenMinter.mint(dcid, (int)dcid_len.getValue());

            byte[] newCid = new byte[QUICHE_MAX_CONN_ID_LEN];
            SECURE_RANDOM.nextBytes(newCid);

            ssize_t generated = LibQuiche.INSTANCE.quiche_retry(scid, scid_len.getPointee(),
                dcid, dcid_len.getPointee(),
                newCid, new size_t(newCid.length),
                token, new size_t(token.length),
                version.getPointee(),
                packetToSend, new size_t(packetToSend.remaining())
            );
            packetToSend.position(packetToSend.position() + generated.intValue());
            if (generated.intValue() < 0)
                throw new IOException("failed to create retry packet: " + quiche_error.errToString(generated.intValue()));
            return true;
        }

        return false;
    }

    /**
     * Fully consumes the {@code packetRead} buffer if the connection was accepted.
     * @return an established connection if accept succeeded, null if accept failed and negotiation should be tried.
     */
    public static JnaQuicheConnection tryAccept(QuicheConfig quicheConfig, TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress local, SocketAddress peer) throws IOException
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scid_len = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcid_len = new size_t_pointer(dcid.length);

        byte[] token = new byte[TokenMinter.MAX_TOKEN_LENGTH];
        size_t_pointer token_len = new size_t_pointer(token.length);

        LOG.debug("getting header info (tryAccept)...");
        int rc = LibQuiche.INSTANCE.quiche_header_info(packetRead, new size_t(packetRead.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scid_len,
            dcid, dcid_len,
            token, token_len);
        if (rc < 0)
            throw new IOException("failed to parse header: " + quiche_error.errToString(rc));

        LOG.debug("version: {}", version);
        LOG.debug("type: {}", type);
        LOG.debug("scid len: {}", scid_len);
        LOG.debug("dcid len: {}", dcid_len);
        LOG.debug("token len: {}", token_len);

        if (!LibQuiche.INSTANCE.quiche_version_is_supported(version.getPointee()))
        {
            LOG.debug("need version negotiation");
            return null;
        }

        if (token_len.getValue() == 0)
        {
            LOG.debug("need stateless retry");
            return null;
        }

        LOG.debug("token validation...");
        // Original Destination Connection ID
        byte[] odcid = tokenValidator.validate(token, (int)token_len.getValue());
        if (odcid == null)
            throw new TokenValidationException("invalid address validation token");
        LOG.debug("validated token");

        LOG.debug("connection creation...");
        LibQuiche.quiche_config libQuicheConfig = buildConfig(quicheConfig);

        SizedStructure<sockaddr> localSockaddr = sockaddr.convert(local);
        SizedStructure<sockaddr> peerSockaddr = sockaddr.convert(peer);
        LibQuiche.quiche_conn quicheConn = LibQuiche.INSTANCE.quiche_accept(dcid, dcid_len.getPointee(), odcid, new size_t(odcid.length), localSockaddr.getStructure(), localSockaddr.getSize(), peerSockaddr.getStructure(), peerSockaddr.getSize(), libQuicheConfig);

        if (quicheConn == null)
        {
            LibQuiche.INSTANCE.quiche_config_free(libQuicheConfig);
            throw new IOException("failed to create connection");
        }

        LOG.debug("connection created");
        JnaQuicheConnection quicheConnection = new JnaQuicheConnection(quicheConn, libQuicheConfig);
        LOG.debug("accepted, immediately receiving the same packet - remaining in buffer: {}", packetRead.remaining());
        while (packetRead.hasRemaining())
        {
            quicheConnection.feedCipherBytes(packetRead, local, peer);
        }
        return quicheConnection;
    }

    public void enableQlog(String filename, String title, String desc) throws IOException
    {
        if (!LibQuiche.INSTANCE.quiche_conn_set_qlog_path(quicheConn, filename, title, desc))
            throw new IOException("unable to set qlog path to " + filename);
    }

    @Override
    protected List<Long> iterableStreamIds(boolean write)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");

            LibQuiche.quiche_stream_iter quiche_stream_iter;
            if (write)
                quiche_stream_iter = LibQuiche.INSTANCE.quiche_conn_writable(quicheConn);
            else
                quiche_stream_iter = LibQuiche.INSTANCE.quiche_conn_readable(quicheConn);

            List<Long> result = new ArrayList<>();
            uint64_t_pointer streamId = new uint64_t_pointer();
            while (LibQuiche.INSTANCE.quiche_stream_iter_next(quiche_stream_iter, streamId))
            {
                result.add(streamId.getValue());
            }
            LibQuiche.INSTANCE.quiche_stream_iter_free(quiche_stream_iter);
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

            LibQuiche.quiche_recv_info info = new LibQuiche.quiche_recv_info();
            SizedStructure<sockaddr> localSockaddr = sockaddr.convert(local);
            info.to = localSockaddr.getStructure().byReference();
            info.to_len = localSockaddr.getSize();
            SizedStructure<sockaddr> peerSockaddr = sockaddr.convert(peer);
            info.from = peerSockaddr.getStructure().byReference();
            info.from_len = peerSockaddr.getSize();
            int received = LibQuiche.INSTANCE.quiche_conn_recv(quicheConn, buffer, new size_t(buffer.remaining()), info).intValue();
            if (received < 0)
                throw new IOException("failed to receive packet; err=" + quiche_error.errToString(received));
            buffer.position(buffer.position() + received);
            return received;
        }
    }

    /**
     * Fill the given buffer with cipher text to be sent.
     * @param buffer the buffer to fill.
     * @return how many bytes were added to the buffer.
     * @throws IOException
     */
    @Override
    public int drainCipherBytes(ByteBuffer buffer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("Cannot send when not connected");

            LibQuiche.quiche_send_info quiche_send_info = new LibQuiche.quiche_send_info();
            quiche_send_info.from = new sockaddr_storage();
            quiche_send_info.from_len = new size_t(quiche_send_info.to.size());
            quiche_send_info.to = new sockaddr_storage();
            quiche_send_info.to_len = new size_t(quiche_send_info.to.size());
            int written = LibQuiche.INSTANCE.quiche_conn_send(quicheConn, buffer, new size_t(buffer.remaining()), quiche_send_info).intValue();
            if (written == quiche_error.QUICHE_ERR_DONE)
                return 0;
            if (written < 0L)
                throw new IOException("failed to send packet; err=" + quiche_error.errToString(written));
            int prevPosition = buffer.position();
            buffer.position(prevPosition + written);
            return written;
        }
    }

    @Override
    public boolean isConnectionClosed()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_is_closed(quicheConn);
        }
    }

    @Override
    public boolean isConnectionEstablished()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_is_established(quicheConn);
        }
    }

    public boolean isConnectionInEarlyData()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_is_in_early_data(quicheConn);
        }
    }

    @Override
    public long nextTimeout()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_timeout_as_millis(quicheConn).longValue();
        }
    }

    @Override
    public void onTimeout()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            LibQuiche.INSTANCE.quiche_conn_on_timeout(quicheConn);
        }
    }

    @Override
    public String getNegotiatedProtocol()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            char_pointer out = new char_pointer();
            size_t_pointer outLen = new size_t_pointer();
            LibQuiche.INSTANCE.quiche_conn_application_proto(quicheConn, out, outLen);
            return out.getValueAsString((int)outLen.getValue(), LibQuiche.CHARSET);
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
            int length = reason == null ? 0 : reason.getBytes(LibQuiche.CHARSET).length;
            int rc = LibQuiche.INSTANCE.quiche_conn_close(quicheConn, true, new uint64_t(error), reason, new size_t(length));
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
                LibQuiche.INSTANCE.quiche_conn_free(quicheConn);
            if (quicheConfig != null)
                LibQuiche.INSTANCE.quiche_config_free(quicheConfig);
            quicheConn = null;
            quicheConfig = null;
        }
    }

    @Override
    public boolean isDraining()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_is_draining(quicheConn);
        }
    }

    @Override
    public int maxLocalStreams()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            LibQuiche.quiche_stats stats = new LibQuiche.quiche_stats();
            LibQuiche.INSTANCE.quiche_conn_stats(quicheConn, stats);
            return stats.peer_initial_max_streams_bidi.intValue();
        }
    }

    @Override
    public long windowCapacity()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            LibQuiche.quiche_path_stats stats = new LibQuiche.quiche_path_stats();
            LibQuiche.INSTANCE.quiche_conn_path_stats(quicheConn, new size_t(0L), stats);
            return stats.cwnd.longValue();
        }
    }

    @Override
    public long windowCapacity(long streamId) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");
            long value = LibQuiche.INSTANCE.quiche_conn_stream_capacity(quicheConn, new uint64_t(streamId)).longValue();
            if (value < 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not read window capacity for stream {} err={}", streamId, quiche_error.errToString(value));
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
            int direction = writeSide ? LibQuiche.quiche_shutdown.QUICHE_SHUTDOWN_WRITE : LibQuiche.quiche_shutdown.QUICHE_SHUTDOWN_READ;
            int rc = LibQuiche.INSTANCE.quiche_conn_stream_shutdown(quicheConn, new uint64_t(streamId), direction, new uint64_t(error));
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
            int written = LibQuiche.INSTANCE.quiche_conn_stream_send(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), last).intValue();
            if (written == quiche_error.QUICHE_ERR_DONE)
                return 0;
            if (written < 0L)
                throw new IOException("failed to write to stream " + streamId + "; err=" + quiche_error.errToString(written));
            buffer.position(buffer.position() + written);
            return written;
        }
    }

    @Override
    public int drainClearBytesForStream(long streamId, ByteBuffer buffer) throws IOException
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IOException("connection was released");
            bool_pointer fin = new bool_pointer();
            int read = LibQuiche.INSTANCE.quiche_conn_stream_recv(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), fin).intValue();
            if (read == quiche_error.QUICHE_ERR_DONE)
                return isStreamFinished(streamId) ? -1 : 0;
            if (read < 0L)
                throw new IOException("failed to read from stream " + streamId + "; err=" + quiche_error.errToString(read));
            buffer.position(buffer.position() + read);
            return read;
        }
    }

    @Override
    public boolean isStreamFinished(long streamId)
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            return LibQuiche.INSTANCE.quiche_conn_stream_finished(quicheConn, new uint64_t(streamId));
        }
    }

    @Override
    public CloseInfo getRemoteCloseInfo()
    {
        try (AutoLock ignore = lock.lock())
        {
            if (quicheConn == null)
                throw new IllegalStateException("connection was released");
            bool_pointer app = new bool_pointer();
            uint64_t_pointer error = new uint64_t_pointer();
            char_pointer reason = new char_pointer();
            size_t_pointer reasonLength = new size_t_pointer();
            if (LibQuiche.INSTANCE.quiche_conn_peer_error(quicheConn, app, error, reason, reasonLength))
                return new CloseInfo(error.getValue(), reason.getValueAsString((int)reasonLength.getValue(), LibQuiche.CHARSET));
            return null;
        }
    }
}
