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

package org.eclipse.jetty.http3.quiche;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.ptr.PointerByReference;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.http3.quiche.ffi.bool_pointer;
import org.eclipse.jetty.http3.quiche.ffi.size_t;
import org.eclipse.jetty.http3.quiche.ffi.size_t_pointer;
import org.eclipse.jetty.http3.quiche.ffi.ssize_t;
import org.eclipse.jetty.http3.quiche.ffi.uint32_t;
import org.eclipse.jetty.http3.quiche.ffi.uint32_t_pointer;
import org.eclipse.jetty.http3.quiche.ffi.uint64_t;
import org.eclipse.jetty.http3.quiche.ffi.uint64_t_pointer;
import org.eclipse.jetty.http3.quiche.ffi.uint8_t_pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.quiche.ffi.LibQuiche.INSTANCE;
import static org.eclipse.jetty.http3.quiche.ffi.LibQuiche.QUICHE_MAX_CONN_ID_LEN;
import static org.eclipse.jetty.http3.quiche.ffi.LibQuiche.quiche_error.QUICHE_ERR_DONE;
import static org.eclipse.jetty.http3.quiche.ffi.LibQuiche.quiche_error.errToString;

public class QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheConnection.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static
    {
        LibQuiche.Logging.enable();
    }

    private LibQuiche.quiche_conn quicheConn;
    private LibQuiche.quiche_config quicheConfig;

    private QuicheConnection(LibQuiche.quiche_conn quicheConn, LibQuiche.quiche_config quicheConfig)
    {
        this.quicheConn = quicheConn;
        this.quicheConfig = quicheConfig;
    }

    public static QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer) throws IOException
    {
        return connect(quicheConfig, peer, LibQuiche.QUICHE_MAX_CONN_ID_LEN);
    }

    public static QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        if (connectionIdLength > LibQuiche.QUICHE_MAX_CONN_ID_LEN)
            throw new IOException("Connection ID length is too large: " + connectionIdLength + " > " + LibQuiche.QUICHE_MAX_CONN_ID_LEN);
        byte[] scid = new byte[connectionIdLength];
        SECURE_RANDOM.nextBytes(scid);
        LibQuiche.quiche_config libQuicheConfig = buildConfig(quicheConfig);
        LibQuiche.quiche_conn quicheConn = INSTANCE.quiche_connect(peer.getHostName(), scid, new size_t(scid.length), libQuicheConfig);
        return new QuicheConnection(quicheConn, libQuicheConfig);
    }

    private static LibQuiche.quiche_config buildConfig(QuicheConfig config) throws IOException
    {
        LibQuiche.quiche_config quicheConfig = INSTANCE.quiche_config_new(new uint32_t(config.getVersion()));
        if (quicheConfig == null)
            throw new IOException("Failed to create quiche config");

        Boolean verifyPeer = config.getVerifyPeer();
        if (verifyPeer != null)
            INSTANCE.quiche_config_verify_peer(quicheConfig, verifyPeer);

        String certChainPemPath = config.getCertChainPemPath();
        if (certChainPemPath != null)
            INSTANCE.quiche_config_load_cert_chain_from_pem_file(quicheConfig, certChainPemPath);

        String privKeyPemPath = config.getPrivKeyPemPath();
        if (privKeyPemPath != null)
            INSTANCE.quiche_config_load_priv_key_from_pem_file(quicheConfig, privKeyPemPath);

        String[] applicationProtos = config.getApplicationProtos();
        if (applicationProtos != null)
        {
            StringBuilder sb = new StringBuilder();
            for (String proto : applicationProtos)
                sb.append((char)proto.length()).append(proto);
            String theProtos = sb.toString();
            INSTANCE.quiche_config_set_application_protos(quicheConfig, theProtos, new size_t(theProtos.length()));
        }

        QuicheConfig.CongestionControl cc = config.getCongestionControl();
        if (cc != null)
            INSTANCE.quiche_config_set_cc_algorithm(quicheConfig, cc.getValue());

        Long maxIdleTimeout = config.getMaxIdleTimeout();
        if (maxIdleTimeout != null)
            INSTANCE.quiche_config_set_max_idle_timeout(quicheConfig, new uint64_t(maxIdleTimeout));

        Long initialMaxData = config.getInitialMaxData();
        if (initialMaxData != null)
            INSTANCE.quiche_config_set_initial_max_data(quicheConfig, new uint64_t(initialMaxData));

        Long initialMaxStreamDataBidiLocal = config.getInitialMaxStreamDataBidiLocal();
        if (initialMaxStreamDataBidiLocal != null)
            INSTANCE.quiche_config_set_initial_max_stream_data_bidi_local(quicheConfig, new uint64_t(initialMaxStreamDataBidiLocal));

        Long initialMaxStreamDataBidiRemote = config.getInitialMaxStreamDataBidiRemote();
        if (initialMaxStreamDataBidiRemote != null)
            INSTANCE.quiche_config_set_initial_max_stream_data_bidi_remote(quicheConfig, new uint64_t(initialMaxStreamDataBidiRemote));

        Long initialMaxStreamDataUni = config.getInitialMaxStreamDataUni();
        if (initialMaxStreamDataUni != null)
            INSTANCE.quiche_config_set_initial_max_stream_data_uni(quicheConfig, new uint64_t(initialMaxStreamDataUni));

        Long initialMaxStreamsBidi = config.getInitialMaxStreamsBidi();
        if (initialMaxStreamsBidi != null)
            INSTANCE.quiche_config_set_initial_max_streams_bidi(quicheConfig, new uint64_t(initialMaxStreamsBidi));

        Long initialMaxStreamsUni = config.getInitialMaxStreamsUni();
        if (initialMaxStreamsUni != null)
            INSTANCE.quiche_config_set_initial_max_streams_uni(quicheConfig, new uint64_t(initialMaxStreamsUni));

        Boolean disableActiveMigration = config.getDisableActiveMigration();
        if (disableActiveMigration != null)
            INSTANCE.quiche_config_set_disable_active_migration(quicheConfig, disableActiveMigration);

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

        byte[] token = new byte[32];
        size_t_pointer token_len = new size_t_pointer(token.length);

        int rc = INSTANCE.quiche_header_info(packet, new size_t(packet.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
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
    public static boolean negotiate(SocketAddress peer, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scid_len = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcid_len = new size_t_pointer(dcid.length);

        byte[] token = new byte[32];
        size_t_pointer token_len = new size_t_pointer(token.length);

        LOG.debug("  getting header info...");
        int rc = INSTANCE.quiche_header_info(packetRead, new size_t(packetRead.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scid_len,
            dcid, dcid_len,
            token, token_len);
        if (rc < 0)
            throw new IOException("failed to parse header: " + LibQuiche.quiche_error.errToString(rc));
        packetRead.position(packetRead.limit());

        LOG.debug("version: {}", version);
        LOG.debug("type: {}", type);
        LOG.debug("scid len: {}", scid_len);
        LOG.debug("dcid len: {}", dcid_len);
        LOG.debug("token len: {}", token_len);

        if (!INSTANCE.quiche_version_is_supported(version.getPointee()))
        {
            LOG.debug("  < version negotiation");

            ssize_t generated = INSTANCE.quiche_negotiate_version(scid, scid_len.getPointee(), dcid, dcid_len.getPointee(), packetToSend, new size_t(packetToSend.remaining()));
            packetToSend.position(packetToSend.position() + generated.intValue());
            if (generated.intValue() < 0)
                throw new IOException("failed to create vneg packet : " + generated);
            return true;
        }

        if (token_len.getValue() == 0)
        {
            LOG.debug("  < stateless retry");

            token = mintToken(dcid, (int)dcid_len.getValue(), peer);

            byte[] newCid = new byte[QUICHE_MAX_CONN_ID_LEN];
            SECURE_RANDOM.nextBytes(newCid);

            ssize_t generated = INSTANCE.quiche_retry(scid, scid_len.getPointee(),
                dcid, dcid_len.getPointee(),
                newCid, new size_t(newCid.length),
                token, new size_t(token.length),
                version.getPointee(),
                packetToSend, new size_t(packetToSend.remaining())
            );
            packetToSend.position(packetToSend.position() + generated.intValue());
            if (generated.intValue() < 0)
                throw new IOException("failed to create retry packet: " + LibQuiche.quiche_error.errToString(generated.intValue()));
            return true;
        }

        return false;
    }

    /**
     * Fully consumes the {@code packetRead} buffer if the connection was accepted.
     * @return an established connection if accept succeeded, null if accept failed and negotiation should be tried.
     */
    public static QuicheConnection tryAccept(QuicheConfig quicheConfig, SocketAddress peer, ByteBuffer packetRead) throws IOException
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scid_len = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcid_len = new size_t_pointer(dcid.length);

        byte[] token = new byte[32];
        size_t_pointer token_len = new size_t_pointer(token.length);

        LOG.debug("  getting header info...");
        int rc = INSTANCE.quiche_header_info(packetRead, new size_t(packetRead.remaining()), new size_t(QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scid_len,
            dcid, dcid_len,
            token, token_len);
        if (rc < 0)
            throw new IOException("failed to parse header: " + LibQuiche.quiche_error.errToString(rc));

        LOG.debug("version: {}", version);
        LOG.debug("type: {}", type);
        LOG.debug("scid len: {}", scid_len);
        LOG.debug("dcid len: {}", dcid_len);
        LOG.debug("token len: {}", token_len);

        if (!INSTANCE.quiche_version_is_supported(version.getPointee()))
        {
            LOG.debug("  < need version negotiation");
            return null;
        }

        if (token_len.getValue() == 0)
        {
            LOG.debug("  < need stateless retry");
            return null;
        }

        LOG.debug("  token validation...");
        // Original Destination Connection ID
        byte[] odcid = validateToken(token, (int)token_len.getValue(), peer);
        if (odcid == null)
            throw new IOException("invalid address validation token");
        LOG.debug("  validated token");

        LOG.debug("  connection creation...");
        LibQuiche.quiche_config libQuicheConfig = buildConfig(quicheConfig);
        LibQuiche.quiche_conn quicheConn = INSTANCE.quiche_accept(dcid, dcid_len.getPointee(), odcid, new size_t(odcid.length), libQuicheConfig);
        if (quicheConn == null)
        {
            INSTANCE.quiche_config_free(libQuicheConfig);
            throw new IOException("failed to create connection");
        }

        LOG.debug("  < connection created");
        QuicheConnection quicheConnection = new QuicheConnection(quicheConn, libQuicheConfig);
        LOG.debug("accepted, immediately receiving the same packet - remaining in buffer: {}", packetRead.remaining());
        while (packetRead.hasRemaining())
        {
            quicheConnection.feedCipherText(packetRead);
        }
        return quicheConnection;
    }

    private static byte[] validateToken(byte[] token, int tokenLength, SocketAddress peer)
    {
        InetSocketAddress inetSocketAddress = (InetSocketAddress)peer;
        ByteBuffer byteBuffer = ByteBuffer.wrap(token).limit(tokenLength);

        byte[] marker = "quiche".getBytes(StandardCharsets.US_ASCII);
        if (byteBuffer.remaining() < marker.length)
            return null;

        byte[] subTokenMarker = new byte[marker.length];
        byteBuffer.get(subTokenMarker);
        if (!Arrays.equals(subTokenMarker, marker))
            return null;

        byte[] address = inetSocketAddress.getAddress().getAddress();
        if (byteBuffer.remaining() < address.length)
            return null;

        byte[] subTokenAddress = new byte[address.length];
        byteBuffer.get(subTokenAddress);
        if (!Arrays.equals(subTokenAddress, address))
            return null;

        byte[] port = ByteBuffer.allocate(Short.BYTES).putShort((short)inetSocketAddress.getPort()).array();
        if (byteBuffer.remaining() < port.length)
            return null;

        byte[] subTokenPort = new byte[port.length];
        byteBuffer.get(subTokenPort);
        if (!Arrays.equals(port, subTokenPort))
            return null;

        byte[] odcid = new byte[byteBuffer.remaining()];
        byteBuffer.get(odcid);

        return odcid;
    }

    private static byte[] mintToken(byte[] dcid, int dcidLength, SocketAddress addr) {
        byte[] quicheBytes = "quiche".getBytes(StandardCharsets.US_ASCII);

        ByteBuffer token = ByteBuffer.allocate(quicheBytes.length + 6 + dcidLength);

        InetSocketAddress inetSocketAddress = (InetSocketAddress)addr;
        byte[] address = inetSocketAddress.getAddress().getAddress();
        int port = inetSocketAddress.getPort();

        token.put(quicheBytes);
        token.put(address);
        token.putShort((short)port);
        token.put(dcid, 0, dcidLength);

        return token.array();
    }

    public synchronized List<Long> readableStreamIds()
    {
        return iterableStreamIds(false);
    }

    public synchronized List<Long> writableStreamIds()
    {
        return iterableStreamIds(true);
    }

    private List<Long> iterableStreamIds(boolean write)
    {
        LibQuiche.quiche_stream_iter quiche_stream_iter;
        if (write)
            quiche_stream_iter = INSTANCE.quiche_conn_writable(quicheConn);
        else
            quiche_stream_iter = INSTANCE.quiche_conn_readable(quicheConn);

        List<Long> result = new ArrayList<>();
        uint64_t_pointer streamId = new uint64_t_pointer();
        while (INSTANCE.quiche_stream_iter_next(quiche_stream_iter, streamId))
        {
            result.add(streamId.getValue());
        }
        INSTANCE.quiche_stream_iter_free(quiche_stream_iter);
        return result;
    }

    /**
     * Read the buffer of cipher text coming from the network.
     * @param buffer the buffer to read.
     * @return how many bytes were consumed.
     * @throws IOException
     */
    public synchronized int feedCipherText(ByteBuffer buffer) throws IOException
    {
        if (quicheConn == null)
            throw new IOException("Cannot receive when not connected");

        int received = INSTANCE.quiche_conn_recv(quicheConn, buffer, new size_t(buffer.remaining())).intValue();
        if (received < 0)
            throw new IOException("Quiche failed to receive packet; err=" + errToString(received));
        buffer.position(buffer.position() + received);
        return received;
    }

    /**
     * Fill the given buffer with cipher text to be sent.
     * @param buffer the buffer to fill.
     * @return how many bytes were added to the buffer.
     * @throws IOException
     */
    public synchronized int drainCipherText(ByteBuffer buffer) throws IOException
    {
        if (quicheConn == null)
            throw new IOException("Cannot send when not connected");
        int written = INSTANCE.quiche_conn_send(quicheConn, buffer, new size_t(buffer.remaining())).intValue();
        if (written == QUICHE_ERR_DONE)
            return 0;
        if (written < 0L)
            throw new IOException("Quiche failed to send packet; err=" + LibQuiche.quiche_error.errToString(written));
        int prevPosition = buffer.position();
        buffer.position(prevPosition + written);
        return written;
    }

    public synchronized boolean isConnectionClosed()
    {
        return INSTANCE.quiche_conn_is_closed(quicheConn);
    }

    public synchronized boolean isConnectionEstablished()
    {
        return INSTANCE.quiche_conn_is_established(quicheConn);
    }

    public synchronized boolean isConnectionInEarlyData()
    {
        return INSTANCE.quiche_conn_is_in_early_data(quicheConn);
    }

    public synchronized long nextTimeout()
    {
        return INSTANCE.quiche_conn_timeout_as_millis(quicheConn).longValue();
    }

    public synchronized void onTimeout()
    {
        INSTANCE.quiche_conn_on_timeout(quicheConn);
    }

    public synchronized String getNegotiatedProtocol()
    {
        PointerByReference out = new PointerByReference();
        size_t_pointer outLen = new size_t_pointer();
        INSTANCE.quiche_conn_application_proto(quicheConn, out, outLen);
        return new String(out.getValue().getByteArray(0, (int)outLen.getValue()), StandardCharsets.UTF_8);
    }

    public synchronized String statistics()
    {
        LibQuiche.quiche_stats stats = new LibQuiche.quiche_stats();
        INSTANCE.quiche_conn_stats(quicheConn, stats);
        return "[recv: " + stats.recv +
            " sent: " + stats.sent +
            " lost: " + stats.lost +
            " rtt: " + stats.rtt +
            " rate: " + stats.delivery_rate +
            " window: " + stats.cwnd +
            "]";
    }

    public synchronized boolean close() throws IOException
    {
        int rc = INSTANCE.quiche_conn_close(quicheConn, true, new uint64_t(0), null, new size_t(0));
        if (rc == 0)
            return true;
        if (rc == QUICHE_ERR_DONE)
            return false;
        throw new IOException("failed to close connection: " + LibQuiche.quiche_error.errToString(rc));
    }

    public synchronized void dispose()
    {
        if (quicheConn != null)
        {
            INSTANCE.quiche_conn_free(quicheConn);
            quicheConn = null;
        }
        if (quicheConfig != null)
        {
            INSTANCE.quiche_config_free(quicheConfig);
            quicheConfig = null;
        }
    }

    public synchronized boolean isDraining()
    {
        return INSTANCE.quiche_conn_is_draining(quicheConn);
    }

    public synchronized long streamCapacity(long streamId) throws IOException
    {
        long value = INSTANCE.quiche_conn_stream_capacity(quicheConn, new uint64_t(streamId)).longValue();
        if (value < 0)
            throw new IOException("Quiche failed to read capacity of stream " + streamId + "; err=" + errToString(value));
        return value;
    }

    public synchronized void shutdownStream(long streamId, boolean writeSide) throws IOException
    {
        int direction = writeSide ? LibQuiche.quiche_shutdown.QUICHE_SHUTDOWN_WRITE : LibQuiche.quiche_shutdown.QUICHE_SHUTDOWN_READ;
        int rc = INSTANCE.quiche_conn_stream_shutdown(quicheConn, new uint64_t(streamId), direction, new uint64_t(0));
        if (rc == 0 || rc == QUICHE_ERR_DONE)
            return;
        throw new IOException("failed to shutdown stream " + streamId + ": " + LibQuiche.quiche_error.errToString(rc));
    }

    public synchronized void feedFinForStream(long streamId) throws IOException
    {
        int written = INSTANCE.quiche_conn_stream_send(quicheConn, new uint64_t(streamId), null, new size_t(0), true).intValue();
        if (written == QUICHE_ERR_DONE)
            return;
        if (written < 0L)
            throw new IOException("Quiche failed to write FIN to stream " + streamId + "; err=" + LibQuiche.quiche_error.errToString(written));
    }

    public synchronized int feedClearTextForStream(long streamId, ByteBuffer buffer) throws IOException
    {
        int written = INSTANCE.quiche_conn_stream_send(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), false).intValue();
        if (written == QUICHE_ERR_DONE)
            return 0;
        if (written < 0L)
            throw new IOException("Quiche failed to write to stream " + streamId + "; err=" + LibQuiche.quiche_error.errToString(written));
        buffer.position(buffer.position() + written);
        return written;
    }

    public synchronized int drainClearTextForStream(long streamId, ByteBuffer buffer) throws IOException
    {
        bool_pointer fin = new bool_pointer();
        int written = INSTANCE.quiche_conn_stream_recv(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), fin).intValue();
        if (written == QUICHE_ERR_DONE)
            return 0;
        if (written < 0L)
            throw new IOException("Quiche failed to read from stream " + streamId + "; err=" + LibQuiche.quiche_error.errToString(written));
        buffer.position(buffer.position() + written);
        return written;
    }

    public synchronized boolean isStreamFinished(long streamId)
    {
        return INSTANCE.quiche_conn_stream_finished(quicheConn, new uint64_t(streamId));
    }
}
