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

package org.eclipse.jetty.quic.quiche.ffi;

import java.nio.ByteBuffer;
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
    String EXPECTED_QUICHE_VERSION = "0.7.0";

    // load the native lib
    LibQuiche INSTANCE = Native.load("quiche", LibQuiche.class);

    class Logging
    {
        private static final Logger LIB_QUICHE_LOG = LoggerFactory.getLogger(LibQuiche.class);
        private static final LoggingCallback LIB_QUICHE_LOGGING_CALLBACK = (msg, argp) -> LIB_QUICHE_LOG.debug(msg);
        private static final AtomicBoolean LOGGING_ENABLED = new AtomicBoolean();

        public static void enable()
        {
            String quicheVersion = INSTANCE.quiche_version();
            if (!EXPECTED_QUICHE_VERSION.equals(quicheVersion))
                throw new IllegalStateException("Native Quiche library version [" + quicheVersion + "] does not match expected version [" + EXPECTED_QUICHE_VERSION + "]");

            if (LIB_QUICHE_LOG.isDebugEnabled() && LOGGING_ENABLED.compareAndSet(false, true))
            {
                INSTANCE.quiche_enable_debug_logging(LIB_QUICHE_LOGGING_CALLBACK, null);
                LIB_QUICHE_LOG.debug("Quiche version {}", quicheVersion);
            }
        }
    }

    // QUIC transport API.

    // The current QUIC wire version.
    int QUICHE_PROTOCOL_VERSION = 0xff00001d;

    // The maximum length of a connection ID.
    int QUICHE_MAX_CONN_ID_LEN = 20;

    // The minimum length of Initial packets sent by a client.
    int QUICHE_MIN_CLIENT_INITIAL_LEN = 1200;

    interface quiche_cc_algorithm {
        int QUICHE_CC_RENO = 0,
        QUICHE_CC_CUBIC = 1;
    }

    interface quiche_error {
        // There is no more work to do.
        long QUICHE_ERR_DONE = -1,

        // The provided buffer is too short.
        QUICHE_ERR_BUFFER_TOO_SHORT = -2,

        // The provided packet cannot be parsed because its version is unknown.
        QUICHE_ERR_UNKNOWN_VERSION = -3,

        // The provided packet cannot be parsed because it contains an invalid
        // frame.
        QUICHE_ERR_INVALID_FRAME = -4,

        // The provided packet cannot be parsed.
        QUICHE_ERR_INVALID_PACKET = -5,

        // The operation cannot be completed because the connection is in an
        // invalid state.
        QUICHE_ERR_INVALID_STATE = -6,

        // The operation cannot be completed because the stream is in an
        // invalid state.
        QUICHE_ERR_INVALID_STREAM_STATE = -7,

        // The peer's transport params cannot be parsed.
        QUICHE_ERR_INVALID_TRANSPORT_PARAM = -8,

        // A cryptographic operation failed.
        QUICHE_ERR_CRYPTO_FAIL = -9,

        // The TLS handshake failed.
        QUICHE_ERR_TLS_FAIL = -10,

        // The peer violated the local flow control limits.
        QUICHE_ERR_FLOW_CONTROL = -11,

        // The peer violated the local stream limits.
        QUICHE_ERR_STREAM_LIMIT = -12,

        // The received data exceeds the stream's final size.
        QUICHE_ERR_FINAL_SIZE = -13,

        // Error in congestion control.
        QUICHE_ERR_CONGESTION_CONTROL = -14;

        static String errToString(long err)
        {
            if (err == QUICHE_ERR_DONE)
                return "QUICHE_ERR_DONE";
            if (err == QUICHE_ERR_BUFFER_TOO_SHORT)
                return "QUICHE_ERR_BUFFER_TOO_SHORT";
            if (err == QUICHE_ERR_UNKNOWN_VERSION)
                return "QUICHE_ERR_UNKNOWN_VERSION";
            if (err == QUICHE_ERR_INVALID_FRAME)
                return "QUICHE_ERR_INVALID_FRAME";
            if (err == QUICHE_ERR_INVALID_PACKET)
                return "QUICHE_ERR_INVALID_PACKET";
            if (err == QUICHE_ERR_INVALID_STATE)
                return "QUICHE_ERR_INVALID_STATE";
            if (err == QUICHE_ERR_INVALID_STREAM_STATE)
                return "QUICHE_ERR_INVALID_STREAM_STATE";
            if (err == QUICHE_ERR_INVALID_TRANSPORT_PARAM)
                return "QUICHE_ERR_INVALID_TRANSPORT_PARAM";
            if (err == QUICHE_ERR_CRYPTO_FAIL)
                return "QUICHE_ERR_CRYPTO_FAIL";
            if (err == QUICHE_ERR_TLS_FAIL)
                return "QUICHE_ERR_TLS_FAIL";
            if (err == QUICHE_ERR_FLOW_CONTROL)
                return "QUICHE_ERR_FLOW_CONTROL";
            if (err == QUICHE_ERR_STREAM_LIMIT)
                return "QUICHE_ERR_STREAM_LIMIT";
            if (err == QUICHE_ERR_FINAL_SIZE)
                return "QUICHE_ERR_FINAL_SIZE";
            if (err == QUICHE_ERR_CONGESTION_CONTROL)
                return "QUICHE_ERR_CONGESTION_CONTROL";
            return "?? " + err;
        }
    }

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
    int quiche_config_set_application_protos(quiche_config config, String protos, size_t protos_len);

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

    // Frees the config object.
    void quiche_config_free(quiche_config config);

    // A QUIC connection.
    @Structure.FieldOrder({"dummy"})
    class quiche_conn extends Structure
    {
        public byte dummy;
    }

    @Structure.FieldOrder({"recv", "sent", "lost", "rtt", "cwnd", "delivery_rate"})
    class quiche_stats extends Structure
    {
        // The number of QUIC packets received on this connection.
        public size_t recv;

        // The number of QUIC packets sent on this connection.
        public size_t sent;

        // The number of QUIC packets that were lost.
        public size_t lost;

        // The estimated round-trip time of the connection (in nanoseconds).
        public uint64_t rtt;

        // The size of the connection's congestion window in bytes.
        public size_t cwnd;

        // The estimated data delivery rate in bytes/s.
        public uint64_t delivery_rate;
    }

    interface LoggingCallback extends Callback
    {
        void log(String msg, Pointer argp);
    }

    // Enables logging. |cb| will be called with log messages
    int quiche_enable_debug_logging(LoggingCallback cb, Pointer argp);

    // Creates a new client-side connection.
    quiche_conn quiche_connect(String server_name, byte[] scid, size_t scid_len, quiche_config config);

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
    quiche_conn quiche_accept(byte[] scid, size_t scid_len, byte[] odcid, size_t odcid_len, quiche_config config);

    // Returns the amount of time until the next timeout event, in milliseconds.
    uint64_t quiche_conn_timeout_as_millis(quiche_conn conn);

    // Processes a timeout event.
    void quiche_conn_on_timeout(quiche_conn conn);

    // Collects and returns statistics about the connection.
    void quiche_conn_stats(quiche_conn conn, quiche_stats out);

    // Writes a single QUIC packet to be sent to the peer.
    ssize_t quiche_conn_send(quiche_conn conn, ByteBuffer out, size_t out_len);

    // Processes QUIC packets received from the peer.
    ssize_t quiche_conn_recv(quiche_conn conn, ByteBuffer buf, size_t buf_len);

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

    // Closes the connection with the given error and reason.
    int quiche_conn_close(quiche_conn conn, boolean app, uint64_t err,
                          String reason, size_t reason_len);

    @Structure.FieldOrder({"dummy"})
    class quiche_stream_iter extends Structure
    {
        public byte dummy;
    }

    interface quiche_shutdown {
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
