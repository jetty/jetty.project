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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LibQuiche extends Library
{
    // This interface is a translation of the quiche.h header of a specific version.
    // It needs to be reviewed each time the native lib version changes.
    String EXPECTED_QUICHE_VERSION = "0.15.0";

    // The charset used to convert java.lang.String to char * and vice versa.
    Charset CHARSET = StandardCharsets.UTF_8;

    // Load the native lib.
    LibQuiche INSTANCE = Native.load("quiche", LibQuiche.class, Map.of(Library.OPTION_STRING_ENCODING, CHARSET.name()));

    class Logging
    {
        private static final Logger LIB_QUICHE_LOG = LoggerFactory.getLogger(LibQuiche.class);
        private static final LoggingCallback LIB_QUICHE_LOGGING_CALLBACK = (msg, argp) -> LIB_QUICHE_LOG.debug(msg);
        private static final AtomicBoolean LOGGING_ENABLED = new AtomicBoolean();

        public static void enable()
        {
            String quicheVersion = INSTANCE.quiche_version();
            if (!EXPECTED_QUICHE_VERSION.equals(quicheVersion))
                throw new IllegalStateException("native quiche library version [" + quicheVersion + "] does not match expected version [" + EXPECTED_QUICHE_VERSION + "]");

            if (LIB_QUICHE_LOG.isDebugEnabled() && LOGGING_ENABLED.compareAndSet(false, true))
            {
                INSTANCE.quiche_enable_debug_logging(LIB_QUICHE_LOGGING_CALLBACK, null);
                LIB_QUICHE_LOG.debug("quiche version {}", quicheVersion);
            }
        }
    }

    // QUIC transport API.

    // Returns a human readable string with the quiche version number.
    String quiche_version();

    // Stores configuration shared between multiple connections.
    @Structure.FieldOrder({"dummy"})
    class quiche_config extends Structure
    {
        public byte dummy;
    }

    // Creates a config object with the given version.
    quiche_config quiche_config_new(uint32_t version);

    // Sets the congestion control algorithm used.
    void quiche_config_set_cc_algorithm(quiche_config config, int/*quiche_cc_algorithm*/ algo);

    // Configures the given certificate chain.
    int quiche_config_load_cert_chain_from_pem_file(quiche_config config, String path);

    // Configures the given private key.
    int quiche_config_load_priv_key_from_pem_file(quiche_config config, String path);

    // Configures whether to verify the peer's certificate.
    void quiche_config_verify_peer(quiche_config config, boolean v);

    // Configures the list of supported application protocols.
    int quiche_config_set_application_protos(quiche_config config, byte[] protos, size_t protos_len);

    // Sets the `max_idle_timeout` transport parameter.
    void quiche_config_set_max_idle_timeout(quiche_config config, uint64_t v);

    // Sets the maximum outgoing UDP payload size.
    void quiche_config_set_max_send_udp_payload_size(quiche_config config, size_t v);

    // Sets the `initial_max_data` transport parameter.
    void quiche_config_set_initial_max_data(quiche_config config, uint64_t v);

    // Sets the `initial_max_stream_data_bidi_local` transport parameter.
    void quiche_config_set_initial_max_stream_data_bidi_local(quiche_config config, uint64_t v);

    // Sets the `initial_max_stream_data_bidi_remote` transport parameter.
    void quiche_config_set_initial_max_stream_data_bidi_remote(quiche_config config, uint64_t v);

    // Sets the `initial_max_stream_data_uni` transport parameter.
    void quiche_config_set_initial_max_stream_data_uni(quiche_config config, uint64_t v);

    // Sets the `initial_max_streams_bidi` transport parameter.
    void quiche_config_set_initial_max_streams_bidi(quiche_config config, uint64_t v);

    // Sets the `initial_max_streams_uni` transport parameter.
    void quiche_config_set_initial_max_streams_uni(quiche_config config, uint64_t v);

    // Sets the `ack_delay_exponent` transport parameter.
    void quiche_config_set_ack_delay_exponent(quiche_config config, uint64_t v);

    // Sets the `max_ack_delay` transport parameter.
    void quiche_config_set_max_ack_delay(quiche_config config, uint64_t v);

    // Sets the `disable_active_migration` transport parameter.
    void quiche_config_set_disable_active_migration(quiche_config config, boolean v);

    // Sets the maximum connection window.
    void quiche_config_set_max_connection_window(quiche_config config, uint64_t v);

    // Sets the maximum stream window.
    void quiche_config_set_max_stream_window(quiche_config config, uint64_t v);

    // Sets the limit of active connection IDs.
    void quiche_config_set_active_connection_id_limit(quiche_config config, uint64_t v);

    // Sets the initial stateless reset token. |v| must contain 16 bytes, otherwise the behaviour is undefined.
    void quiche_config_set_stateless_reset_token(quiche_config config, byte[] v);

    // Frees the config object.
    void quiche_config_free(quiche_config config);

    // A QUIC connection.
    @Structure.FieldOrder({"dummy"})
    class quiche_conn extends Structure
    {
        public byte dummy;
    }

    @Structure.FieldOrder({
        "recv", "sent", "lost", "retrans", "sent_bytes", "recv_bytes", "lost_bytes",
        "stream_retrans_bytes", "paths_count", "peer_max_idle_timeout",
        "peer_max_udp_payload_size", "peer_initial_max_data", "peer_initial_max_stream_data_bidi_local",
        "peer_initial_max_stream_data_bidi_remote", "peer_initial_max_stream_data_uni",
        "peer_initial_max_streams_bidi", "peer_initial_max_streams_uni", "peer_ack_delay_exponent",
        "peer_max_ack_delay", "peer_disable_active_migration", "peer_active_conn_id_limit",
        "peer_max_datagram_frame_size"
    })
    class quiche_stats extends Structure
    {
        // The number of QUIC packets received on this connection.
        public size_t recv;

        // The number of QUIC packets sent on this connection.
        public size_t sent;

        // The number of QUIC packets that were lost.
        public size_t lost;

        // The number of sent QUIC packets with retranmitted data.
        public size_t retrans;

        // The number of sent bytes.
        public uint64_t sent_bytes;

        // The number of received bytes.
        public uint64_t recv_bytes;

        // The number of bytes lost.
        public uint64_t lost_bytes;

        // The number of stream bytes retransmitted.
        public uint64_t stream_retrans_bytes;

        // The number of known paths for the connection.
        public size_t paths_count;

        // The maximum idle timeout.
        public uint64_t peer_max_idle_timeout;

        // The maximum UDP payload size.
        public uint64_t peer_max_udp_payload_size;

        // The initial flow control maximum data for the connection.
        public uint64_t peer_initial_max_data;

        // The initial flow control maximum data for local bidirectional streams.
        public uint64_t peer_initial_max_stream_data_bidi_local;

        // The initial flow control maximum data for remote bidirectional streams.
        public uint64_t peer_initial_max_stream_data_bidi_remote;

        // The initial flow control maximum data for unidirectional streams.
        public uint64_t peer_initial_max_stream_data_uni;

        // The initial maximum bidirectional streams.
        public uint64_t peer_initial_max_streams_bidi;

        // The initial maximum unidirectional streams.
        public uint64_t peer_initial_max_streams_uni;

        // The ACK delay exponent.
        public uint64_t peer_ack_delay_exponent;

        // The max ACK delay.
        public uint64_t peer_max_ack_delay;

        // Whether active migration is disabled.
        public boolean peer_disable_active_migration;

        // The active connection ID limit.
        public uint64_t peer_active_conn_id_limit;

        // DATAGRAM frame extension parameter, if any.
        public ssize_t peer_max_datagram_frame_size;
    }

    @Structure.FieldOrder({
        "local_addr", "local_addr_len", "peer_addr", "peer_addr_len",
        "validation_state", "active", "recv", "sent", "lost", "retrans",
        "rtt", "cwnd", "sent_bytes", "recv_bytes", "lost_bytes",
        "stream_retrans_bytes", "pmtu", "delivery_rate"
    })
    class quiche_path_stats extends Structure
    {
        // The local address used by this path.
        sockaddr_storage local_addr;
        size_t local_addr_len;

        // The peer address seen by this path.
        sockaddr_storage peer_addr;
        size_t peer_addr_len;

        // The validation state of the path.
        ssize_t validation_state;

        // Whether this path is active.
        boolean active;

        // The number of QUIC packets received on this path.
        size_t recv;

        // The number of QUIC packets sent on this path.
        size_t sent;

        // The number of QUIC packets that were lost on this path.
        size_t lost;

        // The number of sent QUIC packets with retransmitted data on this path.
        size_t retrans;

        // The estimated round-trip time of the path (in nanoseconds).
        uint64_t rtt;

        // The size of the path's congestion window in bytes.
        size_t cwnd;

        // The number of sent bytes on this path.
        uint64_t sent_bytes;

        // The number of received bytes on this path.
        uint64_t recv_bytes;

        // The number of bytes lost on this path.
        uint64_t lost_bytes;

        // The number of stream bytes retransmitted on this path.
        uint64_t stream_retrans_bytes;

        // The current PMTU for the path.
        size_t pmtu;

        // The most recent data delivery rate estimate in bytes/s.
        uint64_t delivery_rate;
    }

    interface LoggingCallback extends Callback
    {
        void log(String msg, Pointer argp);
    }

    // Enables logging. |cb| will be called with log messages
    int quiche_enable_debug_logging(LoggingCallback cb, Pointer argp);

    // Creates a new client-side connection.
    quiche_conn quiche_connect(String server_name, byte[] scid, size_t scid_len,
                               sockaddr local, size_t local_len,
                               sockaddr peer, size_t peer_len,
                               quiche_config config);

    interface packet_type
    {
        byte INITIAL = 1,
            RETRY = 2,
            HANDSHAKE = 3,
            ZERO_RTT = 4,
            SHORT = 5,
            VERSION_NEGOTIATION = 6;

        static String typeToString(byte type)
        {
            if (type == INITIAL)
                return "INITIAL";
            if (type == RETRY)
                return "RETRY";
            if (type == HANDSHAKE)
                return "HANDSHAKE";
            if (type == ZERO_RTT)
                return "ZERO_RTT";
            if (type == SHORT)
                return "SHORT";
            if (type == VERSION_NEGOTIATION)
                return "VERSION_NEGOTIATION";
            return "?? " + type;
        }
    }

    // Extracts version, type, source / destination connection ID and address
    // verification token from the packet in |buf|.
    int quiche_header_info(ByteBuffer buf, size_t buf_len, size_t dcil,
                           uint32_t_pointer version, uint8_t_pointer type,
                           byte[] scid, size_t_pointer scid_len,
                           byte[] dcid, size_t_pointer dcid_len,
                           byte[] token, size_t_pointer token_len);

    // Returns true if the given protocol version is supported.
    boolean quiche_version_is_supported(uint32_t version);

    // Enables qlog to the specified file path. Returns true on success.
    boolean quiche_conn_set_qlog_path(quiche_conn conn, String path,
                                      String log_title, String log_desc);

    // Writes a version negotiation packet.
    ssize_t quiche_negotiate_version(byte[] scid, size_t scid_len,
                                     byte[] dcid, size_t dcid_len,
                                     ByteBuffer out, size_t out_len);

    // Writes a retry packet.
    ssize_t quiche_retry(byte[] scid, size_t scid_len,
                         byte[] dcid, size_t dcid_len,
                         byte[] new_scid, size_t new_scid_len,
                         byte[] token, size_t token_len,
                         uint32_t version, ByteBuffer out, size_t out_len);

    // Creates a new server-side connection.
    quiche_conn quiche_accept(byte[] scid, size_t scid_len, byte[] odcid, size_t odcid_len,
                              sockaddr local, size_t local_len,
                              sockaddr peer, size_t peer_len,
                              quiche_config config);

    // Returns the amount of time until the next timeout event, in milliseconds.
    uint64_t quiche_conn_timeout_as_millis(quiche_conn conn);

    // Processes a timeout event.
    void quiche_conn_on_timeout(quiche_conn conn);

    // Collects and returns statistics about the connection.
    void quiche_conn_stats(quiche_conn conn, quiche_stats out);

    // Collects and returns statistics about the specified path for the connection.
    //
    // The `idx` argument represent the path's index (also see the `paths_count`
    // field of `quiche_stats`).
    int quiche_conn_path_stats(quiche_conn conn, size_t idx, quiche_path_stats out);

    @Structure.FieldOrder({"from", "from_len", "to", "to_len", "at"})
    class quiche_send_info extends Structure
    {
        // The local address the packet should be sent from.
        public sockaddr_storage from;
        public size_t from_len;

        // The remote address the packet should be sent to.
        public sockaddr_storage to;
        public size_t to_len;

        // The time to send the packet out.
        public timespec at;
    }

    // Writes a single QUIC packet to be sent to the peer.
    ssize_t quiche_conn_send(quiche_conn conn, ByteBuffer out, size_t out_len, quiche_send_info out_info);

    // Returns the size of the send quantum, in bytes.
    size_t quiche_conn_send_quantum(quiche_conn conn);

    @Structure.FieldOrder({"from", "from_len", "to", "to_len"})
    class quiche_recv_info extends Structure
    {
        // The remote address the packet was received from.
        public sockaddr.ByReference from;
        public size_t from_len;

        // The local address the packet was received on.
        public sockaddr.ByReference to;
        public size_t to_len;
    }

    // Processes QUIC packets received from the peer.
    ssize_t quiche_conn_recv(quiche_conn conn, ByteBuffer buf, size_t buf_len, quiche_recv_info info);

    // Returns the negotiated ALPN protocol.
    void quiche_conn_application_proto(quiche_conn conn, char_pointer out, size_t_pointer out_len);

    // Returns true if the connection handshake is complete.
    boolean quiche_conn_is_established(quiche_conn conn);

    // Returns true if the connection has a pending handshake that has progressed
    // enough to send or receive early data.
    boolean quiche_conn_is_in_early_data(quiche_conn conn);

    // Returns true if the connection is draining.
    boolean quiche_conn_is_draining(quiche_conn conn);

    // Returns true if the connection is closed.
    boolean quiche_conn_is_closed(quiche_conn conn);

    // Returns true if the connection was closed due to the idle timeout.
    boolean quiche_conn_is_timed_out(quiche_conn conn);

    // Returns true if a connection error was received, and updates the provided
    // parameters accordingly.
    boolean quiche_conn_peer_error(quiche_conn conn,
                                   bool_pointer is_app,
                                   uint64_t_pointer error_code,
                                   char_pointer reason,
                                   size_t_pointer reason_len);

    // Returns true if a connection error was queued or sent, and updates the provided
    // parameters accordingly.
    boolean quiche_conn_local_error(quiche_conn conn,
                                    bool_pointer is_app,
                                    uint64_t_pointer error_code,
                                    char_pointer reason,
                                    size_t_pointer reason_len);

    // Closes the connection with the given error and reason.
    int quiche_conn_close(quiche_conn conn, boolean app, uint64_t err,
                          String reason, size_t reason_len);

    @Structure.FieldOrder({"dummy"})
    class quiche_stream_iter extends Structure
    {
        public byte dummy;
    }

    interface quiche_shutdown
    {
        int QUICHE_SHUTDOWN_READ = 0,
            QUICHE_SHUTDOWN_WRITE = 1;
    }

    // Sets the priority for a stream.
    int quiche_conn_stream_priority(quiche_conn conn, uint64_t stream_id,
                                    uint8_t urgency, boolean incremental);

    // Shuts down reading or writing from/to the specified stream.
    int quiche_conn_stream_shutdown(quiche_conn conn, uint64_t stream_id,
                                    int /*quiche_shutdown*/ direction, uint64_t err);

    ssize_t quiche_conn_stream_capacity(quiche_conn conn, uint64_t stream_id);

    // Returns true if all the data has been read from the specified stream.
    boolean quiche_conn_stream_finished(quiche_conn conn, uint64_t stream_id);

    // Returns an iterator over streams that have outstanding data to read.
    quiche_stream_iter quiche_conn_readable(quiche_conn conn);

    // Returns an iterator over streams that can be written to.
    quiche_stream_iter quiche_conn_writable(quiche_conn conn);

    // Fetches the next stream from the given iterator. Returns false if there are
    // no more elements in the iterator.
    boolean quiche_stream_iter_next(quiche_stream_iter iter, uint64_t_pointer stream_id);

    // Frees the given stream iterator object.
    void quiche_stream_iter_free(quiche_stream_iter iter);

    // Reads contiguous data from a stream.
    ssize_t quiche_conn_stream_recv(quiche_conn conn, uint64_t stream_id, ByteBuffer out, size_t buf_len, bool_pointer fin);

    // Writes data to a stream.
    ssize_t quiche_conn_stream_send(quiche_conn conn, uint64_t stream_id, ByteBuffer buf, size_t buf_len, boolean fin);

    // Frees the connection object.
    void quiche_conn_free(quiche_conn conn);
}
