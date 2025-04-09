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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_BOOL;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_CHAR;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_INT;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_LONG;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_POINTER;

public class quiche_h
{
    private static final String EXPECTED_QUICHE_VERSION = "0.23.5";
    private static final Logger LOG = LoggerFactory.getLogger(quiche_h.class);

    static void initialize()
    {
        String quicheVersion = quiche_version().getString(0L, StandardCharsets.UTF_8);
        if (!EXPECTED_QUICHE_VERSION.equals(quicheVersion))
            throw new IllegalStateException("Native Quiche library version [" + quicheVersion + "] does not match expected version [" + EXPECTED_QUICHE_VERSION + "]");

        if (LOG.isDebugEnabled())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Quiche version {}", quicheVersion);

            MemorySegment cb = NativeHelper.upcallMemorySegment(LoggingCallback.class, "log", LoggingCallback.INSTANCE,
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER), LoggingCallback.SCOPE);

            if (quiche_enable_debug_logging(cb, MemorySegment.NULL) != 0)
                throw new AssertionError("Cannot enable quiche debug logging");
        }
    }

    private static class LoggingCallback
    {
        private static final LoggingCallback INSTANCE = new LoggingCallback();
        private static final Arena SCOPE = Arena.ofAuto();

        public void log(MemorySegment msg, MemorySegment argp)
        {
            LOG.debug(msg.getString(0L, StandardCharsets.UTF_8));
        }
    }

    private static class DowncallHandles
    {
        private static final MethodHandle quiche_version = NativeHelper.downcallHandle(
            "quiche_version",
            FunctionDescriptor.of(C_POINTER)
        );
        private static final MethodHandle quiche_enable_debug_logging = NativeHelper.downcallHandle(
            "quiche_enable_debug_logging",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_new = NativeHelper.downcallHandle(
            "quiche_config_new",
            FunctionDescriptor.of(
                C_POINTER,
                C_INT
            ));
        private static final MethodHandle quiche_config_load_cert_chain_from_pem_file = NativeHelper.downcallHandle(
            "quiche_config_load_cert_chain_from_pem_file",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_load_priv_key_from_pem_file = NativeHelper.downcallHandle(
            "quiche_config_load_priv_key_from_pem_file",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_load_verify_locations_from_file = NativeHelper.downcallHandle(
            "quiche_config_load_verify_locations_from_file",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_load_verify_locations_from_directory = NativeHelper.downcallHandle(
            "quiche_config_load_verify_locations_from_directory",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_verify_peer = NativeHelper.downcallHandle(
            "quiche_config_verify_peer",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_grease = NativeHelper.downcallHandle(
            "quiche_config_grease",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_log_keys = NativeHelper.downcallHandle(
            "quiche_config_log_keys",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_config_enable_early_data = NativeHelper.downcallHandle(
            "quiche_config_enable_early_data",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_config_set_application_protos = NativeHelper.downcallHandle(
            "quiche_config_set_application_protos",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_idle_timeout = NativeHelper.downcallHandle(
            "quiche_config_set_max_idle_timeout",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_recv_udp_payload_size = NativeHelper.downcallHandle(
            "quiche_config_set_max_recv_udp_payload_size",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_send_udp_payload_size = NativeHelper.downcallHandle(
            "quiche_config_set_max_send_udp_payload_size",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_data = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_data",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_stream_data_bidi_local = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_stream_data_bidi_local",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_stream_data_bidi_remote = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_stream_data_bidi_remote",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_stream_data_uni = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_stream_data_uni",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_streams_bidi = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_streams_bidi",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_initial_max_streams_uni = NativeHelper.downcallHandle(
            "quiche_config_set_initial_max_streams_uni",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_ack_delay_exponent = NativeHelper.downcallHandle(
            "quiche_config_set_ack_delay_exponent",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_ack_delay = NativeHelper.downcallHandle(
            "quiche_config_set_max_ack_delay",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_disable_active_migration = NativeHelper.downcallHandle(
            "quiche_config_set_disable_active_migration",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_set_cc_algorithm_name = NativeHelper.downcallHandle(
            "quiche_config_set_cc_algorithm_name",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_set_initial_congestion_window_packets = NativeHelper.downcallHandle(
            "quiche_config_set_initial_congestion_window_packets",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_cc_algorithm = NativeHelper.downcallHandle(
            "quiche_config_set_cc_algorithm",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_INT
            ));
        private static final MethodHandle quiche_config_enable_hystart = NativeHelper.downcallHandle(
            "quiche_config_enable_hystart",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_enable_pacing = NativeHelper.downcallHandle(
            "quiche_config_enable_pacing",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_set_max_pacing_rate = NativeHelper.downcallHandle(
            "quiche_config_set_max_pacing_rate",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_enable_dgram = NativeHelper.downcallHandle(
            "quiche_config_enable_dgram",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL,
                C_LONG,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_connection_window = NativeHelper.downcallHandle(
            "quiche_config_set_max_connection_window",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_max_stream_window = NativeHelper.downcallHandle(
            "quiche_config_set_max_stream_window",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_active_connection_id_limit = NativeHelper.downcallHandle(
            "quiche_config_set_active_connection_id_limit",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_set_stateless_reset_token = NativeHelper.downcallHandle(
            "quiche_config_set_stateless_reset_token",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_config_set_disable_dcid_reuse = NativeHelper.downcallHandle(
            "quiche_config_set_disable_dcid_reuse",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_config_set_ticket_key = NativeHelper.downcallHandle(
            "quiche_config_set_ticket_key",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_config_free = NativeHelper.downcallHandle(
            "quiche_config_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_header_info = NativeHelper.downcallHandle(
            "quiche_header_info",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_accept = NativeHelper.downcallHandle(
            "quiche_accept",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_connect = NativeHelper.downcallHandle(
            "quiche_connect",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_negotiate_version = NativeHelper.downcallHandle(
            "quiche_negotiate_version",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_retry = NativeHelper.downcallHandle(
            "quiche_retry",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_INT,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_version_is_supported = NativeHelper.downcallHandle(
            "quiche_version_is_supported",
            FunctionDescriptor.of(
                C_BOOL,
                C_INT
            ));
        private static final MethodHandle quiche_conn_new_with_tls = NativeHelper.downcallHandle(
            "quiche_conn_new_with_tls",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_POINTER,
                C_BOOL
            ));
        private static final MethodHandle quiche_conn_set_keylog_path = NativeHelper.downcallHandle(
            "quiche_conn_set_keylog_path",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_set_keylog_fd = NativeHelper.downcallHandle(
            "quiche_conn_set_keylog_fd",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_INT
            ));
        private static final MethodHandle quiche_conn_set_qlog_path = NativeHelper.downcallHandle(
            "quiche_conn_set_qlog_path",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_set_qlog_fd = NativeHelper.downcallHandle(
            "quiche_conn_set_qlog_fd",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_INT,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_set_session = NativeHelper.downcallHandle(
            "quiche_conn_set_session",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_recv = NativeHelper.downcallHandle(
            "quiche_conn_recv",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send = NativeHelper.downcallHandle(
            "quiche_conn_send",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send_quantum = NativeHelper.downcallHandle(
            "quiche_conn_send_quantum",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send_on_path = NativeHelper.downcallHandle(
            "quiche_conn_send_on_path",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send_quantum_on_path = NativeHelper.downcallHandle(
            "quiche_conn_send_quantum_on_path",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT
            ));
        private static final MethodHandle quiche_conn_stream_recv = NativeHelper.downcallHandle(
            "quiche_conn_stream_recv",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_stream_send = NativeHelper.downcallHandle(
            "quiche_conn_stream_send",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG,
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_stream_priority = NativeHelper.downcallHandle(
            "quiche_conn_stream_priority",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_CHAR,
                C_BOOL
            ));
        private static final MethodHandle quiche_conn_stream_shutdown = NativeHelper.downcallHandle(
            "quiche_conn_stream_shutdown",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_INT,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_stream_capacity = NativeHelper.downcallHandle(
            "quiche_conn_stream_capacity",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_stream_readable = NativeHelper.downcallHandle(
            "quiche_conn_stream_readable",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_stream_readable_next = NativeHelper.downcallHandle(
            "quiche_conn_stream_readable_next",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_stream_writable = NativeHelper.downcallHandle(
            "quiche_conn_stream_writable",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_stream_writable_next = NativeHelper.downcallHandle(
            "quiche_conn_stream_writable_next",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_stream_finished = NativeHelper.downcallHandle(
            "quiche_conn_stream_finished",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_readable = NativeHelper.downcallHandle(
            "quiche_conn_readable",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_writable = NativeHelper.downcallHandle(
            "quiche_conn_writable",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_max_send_udp_payload_size = NativeHelper.downcallHandle(
            "quiche_conn_max_send_udp_payload_size",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_timeout_as_nanos = NativeHelper.downcallHandle(
            "quiche_conn_timeout_as_nanos",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_timeout_as_millis = NativeHelper.downcallHandle(
            "quiche_conn_timeout_as_millis",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_on_timeout = NativeHelper.downcallHandle(
            "quiche_conn_on_timeout",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_close = NativeHelper.downcallHandle(
            "quiche_conn_close",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_BOOL,
                C_LONG,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_trace_id = NativeHelper.downcallHandle(
            "quiche_conn_trace_id",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_source_id = NativeHelper.downcallHandle(
            "quiche_conn_source_id",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_source_ids = NativeHelper.downcallHandle(
            "quiche_conn_source_ids",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_connection_id_iter_next = NativeHelper.downcallHandle(
            "quiche_connection_id_iter_next",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_connection_id_iter_free = NativeHelper.downcallHandle(
            "quiche_connection_id_iter_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_destination_id = NativeHelper.downcallHandle(
            "quiche_conn_destination_id",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_application_proto = NativeHelper.downcallHandle(
            "quiche_conn_application_proto",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_peer_cert = NativeHelper.downcallHandle(
            "quiche_conn_peer_cert",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_session = NativeHelper.downcallHandle(
            "quiche_conn_session",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_established = NativeHelper.downcallHandle(
            "quiche_conn_is_established",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_resumed = NativeHelper.downcallHandle(
            "quiche_conn_is_resumed",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_in_early_data = NativeHelper.downcallHandle(
            "quiche_conn_is_in_early_data",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_readable = NativeHelper.downcallHandle(
            "quiche_conn_is_readable",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_draining = NativeHelper.downcallHandle(
            "quiche_conn_is_draining",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_peer_streams_left_bidi = NativeHelper.downcallHandle(
            "quiche_conn_peer_streams_left_bidi",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_peer_streams_left_uni = NativeHelper.downcallHandle(
            "quiche_conn_peer_streams_left_uni",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_closed = NativeHelper.downcallHandle(
            "quiche_conn_is_closed",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_timed_out = NativeHelper.downcallHandle(
            "quiche_conn_is_timed_out",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_peer_error = NativeHelper.downcallHandle(
            "quiche_conn_peer_error",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_local_error = NativeHelper.downcallHandle(
            "quiche_conn_local_error",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_stream_iter_next = NativeHelper.downcallHandle(
            "quiche_stream_iter_next",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_stream_iter_free = NativeHelper.downcallHandle(
            "quiche_stream_iter_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_stats = NativeHelper.downcallHandle(
            "quiche_conn_stats",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_peer_transport_params = NativeHelper.downcallHandle(
            "quiche_conn_peer_transport_params",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_path_stats = NativeHelper.downcallHandle(
            "quiche_conn_path_stats",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_server = NativeHelper.downcallHandle(
            "quiche_conn_is_server",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send_ack_eliciting = NativeHelper.downcallHandle(
            "quiche_conn_send_ack_eliciting",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_send_ack_eliciting_on_path = NativeHelper.downcallHandle(
            "quiche_conn_send_ack_eliciting_on_path",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT
            ));
        private static final MethodHandle quiche_conn_retired_scid_next = NativeHelper.downcallHandle(
            "quiche_conn_retired_scid_next",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_retired_scids = NativeHelper.downcallHandle(
            "quiche_conn_retired_scids",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_available_dcids = NativeHelper.downcallHandle(
            "quiche_conn_available_dcids",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_scids_left = NativeHelper.downcallHandle(
            "quiche_conn_scids_left",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_active_scids = NativeHelper.downcallHandle(
            "quiche_conn_active_scids",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_new_scid = NativeHelper.downcallHandle(
            "quiche_conn_new_scid",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_BOOL,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_probe_path = NativeHelper.downcallHandle(
            "quiche_conn_probe_path",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_migrate_source = NativeHelper.downcallHandle(
            "quiche_conn_migrate_source",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_migrate = NativeHelper.downcallHandle(
            "quiche_conn_migrate",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_INT,
                C_POINTER,
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_path_event_next = NativeHelper.downcallHandle(
            "quiche_conn_path_event_next",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_type = NativeHelper.downcallHandle(
            "quiche_path_event_type",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_new = NativeHelper.downcallHandle(
            "quiche_path_event_new",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_validated = NativeHelper.downcallHandle(
            "quiche_path_event_validated",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_failed_validation = NativeHelper.downcallHandle(
            "quiche_path_event_failed_validation",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_closed = NativeHelper.downcallHandle(
            "quiche_path_event_closed",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_reused_source_connection_id = NativeHelper.downcallHandle(
            "quiche_path_event_reused_source_connection_id",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_peer_migrated = NativeHelper.downcallHandle(
            "quiche_path_event_peer_migrated",
            FunctionDescriptor.ofVoid(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_path_event_free = NativeHelper.downcallHandle(
            "quiche_path_event_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_retire_dcid = NativeHelper.downcallHandle(
            "quiche_conn_retire_dcid",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_paths_iter = NativeHelper.downcallHandle(
            "quiche_conn_paths_iter",
            FunctionDescriptor.of(
                C_POINTER,
                C_POINTER,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_socket_addr_iter_next = NativeHelper.downcallHandle(
            "quiche_socket_addr_iter_next",
            FunctionDescriptor.of(
                C_BOOL,
                C_POINTER,
                C_POINTER,
                C_POINTER
            ));
        private static final MethodHandle quiche_socket_addr_iter_free = NativeHelper.downcallHandle(
            "quiche_socket_addr_iter_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_conn_is_path_validated = NativeHelper.downcallHandle(
            "quiche_conn_is_path_validated",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_POINTER,
                C_LONG,
                C_POINTER,
                C_LONG
            ));
        private static final MethodHandle quiche_conn_free = NativeHelper.downcallHandle(
            "quiche_conn_free",
            FunctionDescriptor.ofVoid(
                C_POINTER
            ));
        private static final MethodHandle quiche_put_varint = NativeHelper.downcallHandle(
            "quiche_put_varint",
            FunctionDescriptor.of(
                C_INT,
                C_POINTER,
                C_LONG,
                C_LONG
            ));
        private static final MethodHandle quiche_get_varint = NativeHelper.downcallHandle(
            "quiche_get_varint",
            FunctionDescriptor.of(
                C_LONG,
                C_POINTER,
                C_LONG,
                C_POINTER
            ));
    }

    public static MemorySegment quiche_version()
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_version.invokeExact();
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_enable_debug_logging(MemorySegment cb, MemorySegment argp)
    {
        try
        {
            return (int)DowncallHandles.quiche_enable_debug_logging.invokeExact(cb, argp);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_config_new(int version)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_config_new.invokeExact(version);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_load_cert_chain_from_pem_file(MemorySegment config, MemorySegment path)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_load_cert_chain_from_pem_file.invokeExact(config, path);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_load_priv_key_from_pem_file(MemorySegment config, MemorySegment path)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_load_priv_key_from_pem_file.invokeExact(config, path);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_load_verify_locations_from_file(MemorySegment config, MemorySegment path)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_load_verify_locations_from_file.invokeExact(config, path);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_load_verify_locations_from_directory(MemorySegment config, MemorySegment path)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_load_verify_locations_from_directory.invokeExact(config, path);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_verify_peer(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_verify_peer.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_grease(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_grease.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_log_keys(MemorySegment config)
    {
        try
        {
            DowncallHandles.quiche_config_log_keys.invokeExact(config);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_enable_early_data(MemorySegment config)
    {
        try
        {
            DowncallHandles.quiche_config_enable_early_data.invokeExact(config);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_set_application_protos(MemorySegment config, MemorySegment protos, long protos_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_set_application_protos.invokeExact(config, protos, protos_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_idle_timeout(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_idle_timeout.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_recv_udp_payload_size(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_recv_udp_payload_size.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_send_udp_payload_size(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_send_udp_payload_size.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_data(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_data.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_bidi_local(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_stream_data_bidi_local.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_bidi_remote(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_stream_data_bidi_remote.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_uni(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_stream_data_uni.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_streams_bidi(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_streams_bidi.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_max_streams_uni(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_max_streams_uni.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_ack_delay_exponent(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_ack_delay_exponent.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_ack_delay(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_ack_delay.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_disable_active_migration(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_set_disable_active_migration.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_set_cc_algorithm_name(MemorySegment config, MemorySegment algo)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_set_cc_algorithm_name.invokeExact(config, algo);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_initial_congestion_window_packets(MemorySegment config, long packets)
    {
        try
        {
            DowncallHandles.quiche_config_set_initial_congestion_window_packets.invokeExact(config, packets);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_cc_algorithm(MemorySegment config, int algo)
    {
        try
        {
            DowncallHandles.quiche_config_set_cc_algorithm.invokeExact(config, algo);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_enable_hystart(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_enable_hystart.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_enable_pacing(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_enable_pacing.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_pacing_rate(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_pacing_rate.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_enable_dgram(MemorySegment config, boolean enabled, long recv_queue_len, long send_queue_len)
    {
        try
        {
            DowncallHandles.quiche_config_enable_dgram.invokeExact(config, (byte)(enabled ? 1 : 0), recv_queue_len, send_queue_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_connection_window(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_connection_window.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_max_stream_window(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_max_stream_window.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_active_connection_id_limit(MemorySegment config, long v)
    {
        try
        {
            DowncallHandles.quiche_config_set_active_connection_id_limit.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_stateless_reset_token(MemorySegment config, MemorySegment v)
    {
        try
        {
            DowncallHandles.quiche_config_set_stateless_reset_token.invokeExact(config, v);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_set_disable_dcid_reuse(MemorySegment config, boolean v)
    {
        try
        {
            DowncallHandles.quiche_config_set_disable_dcid_reuse.invokeExact(config, (byte)(v ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_config_set_ticket_key(MemorySegment config, MemorySegment key, long key_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_config_set_ticket_key.invokeExact(config, key, key_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_config_free(MemorySegment config)
    {
        try
        {
            DowncallHandles.quiche_config_free.invokeExact(config);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_header_info(MemorySegment buf, long buf_len, long dcil, MemorySegment version, MemorySegment type, MemorySegment scid, MemorySegment scid_len, MemorySegment dcid, MemorySegment dcid_len, MemorySegment token, MemorySegment token_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_header_info.invokeExact(buf, buf_len, dcil, version, type, scid, scid_len, dcid, dcid_len, token, token_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_accept(MemorySegment scid, long scid_len, MemorySegment odcid, long odcid_len, MemorySegment local, int local_len, MemorySegment peer, int peer_len, MemorySegment config)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_accept.invokeExact(scid, scid_len, odcid, odcid_len, local, local_len, peer, peer_len, config);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_connect(MemorySegment server_name, MemorySegment scid, long scid_len, MemorySegment local, int local_len, MemorySegment peer, int peer_len, MemorySegment config)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_connect.invokeExact(server_name, scid, scid_len, local, local_len, peer, peer_len, config);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_negotiate_version(MemorySegment scid, long scid_len, MemorySegment dcid, long dcid_len, MemorySegment out, long out_len)
    {
        try
        {
            return (long)DowncallHandles.quiche_negotiate_version.invokeExact(scid, scid_len, dcid, dcid_len, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_retry(MemorySegment scid, long scid_len, MemorySegment dcid, long dcid_len, MemorySegment new_scid, long new_scid_len, MemorySegment token, long token_len, int version, MemorySegment out, long out_len)
    {
        try
        {
            return (long)DowncallHandles.quiche_retry.invokeExact(scid, scid_len, dcid, dcid_len, new_scid, new_scid_len, token, token_len, version, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_version_is_supported(int version)
    {
        try
        {
            return (byte)DowncallHandles.quiche_version_is_supported.invokeExact(version) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_new_with_tls(MemorySegment scid, long scid_len, MemorySegment odcid, long odcid_len, MemorySegment local, int local_len, MemorySegment peer, int peer_len, MemorySegment config, MemorySegment ssl, boolean is_server)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_new_with_tls.invokeExact(scid, scid_len, odcid, odcid_len, local, local_len, peer, peer_len, config, ssl, (byte)(is_server ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_set_keylog_path(MemorySegment conn, MemorySegment path)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_set_keylog_path.invokeExact(conn, path) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_set_keylog_fd(MemorySegment conn, int fd)
    {
        try
        {
            DowncallHandles.quiche_conn_set_keylog_fd.invokeExact(conn, fd);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_set_qlog_path(MemorySegment conn, MemorySegment path, MemorySegment log_title, MemorySegment log_desc)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_set_qlog_path.invokeExact(conn, path, log_title, log_desc) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_set_qlog_fd(MemorySegment conn, int fd, MemorySegment log_title, MemorySegment log_desc)
    {
        try
        {
            DowncallHandles.quiche_conn_set_qlog_fd.invokeExact(conn, fd, log_title, log_desc);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_set_session(MemorySegment conn, MemorySegment buf, long buf_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_set_session.invokeExact(conn, buf, buf_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_recv(MemorySegment conn, MemorySegment buf, long buf_len, MemorySegment info)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_recv.invokeExact(conn, buf, buf_len, info);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send(MemorySegment conn, MemorySegment out, long out_len, MemorySegment out_info)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send.invokeExact(conn, out, out_len, out_info);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send_quantum(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send_quantum.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send_on_path(MemorySegment conn, MemorySegment out, long out_len, MemorySegment from, int from_len, MemorySegment to, int to_len, MemorySegment out_info)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send_on_path.invokeExact(conn, out, out_len, from, from_len, to, to_len, out_info);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send_quantum_on_path(MemorySegment conn, MemorySegment local_addr, int local_len, MemorySegment peer_addr, int peer_len)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send_quantum_on_path.invokeExact(conn, local_addr, local_len, peer_addr, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_stream_recv(MemorySegment conn, long stream_id, MemorySegment out, long buf_len, MemorySegment fin, MemorySegment out_error_code)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_stream_recv.invokeExact(conn, stream_id, out, buf_len, fin, out_error_code);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_stream_send(MemorySegment conn, long stream_id, MemorySegment buf, long buf_len, boolean fin, MemorySegment out_error_code)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_stream_send.invokeExact(conn, stream_id, buf, buf_len, (byte)(fin ? 1 : 0), out_error_code);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_stream_priority(MemorySegment conn, long stream_id, byte urgency, boolean incremental)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_stream_priority.invokeExact(conn, stream_id, urgency, (byte)(incremental ? 1 : 0));
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static class quiche_shutdown
    {
        public static final int QUICHE_SHUTDOWN_READ = 0,
            QUICHE_SHUTDOWN_WRITE = 1;
    }

    public static int quiche_conn_stream_shutdown(MemorySegment conn, long stream_id, int direction, long err)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_stream_shutdown.invokeExact(conn, stream_id, direction, err);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_stream_capacity(MemorySegment conn, long stream_id)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_stream_capacity.invokeExact(conn, stream_id);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_stream_readable(MemorySegment conn, long stream_id)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_stream_readable.invokeExact(conn, stream_id) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_stream_readable_next(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_stream_readable_next.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_stream_writable(MemorySegment conn, long stream_id, long len)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_stream_writable.invokeExact(conn, stream_id, len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_stream_writable_next(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_stream_writable_next.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_stream_finished(MemorySegment conn, long stream_id)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_stream_finished.invokeExact(conn, stream_id) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_readable(MemorySegment conn)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_readable.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_writable(MemorySegment conn)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_writable.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_max_send_udp_payload_size(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_max_send_udp_payload_size.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_timeout_as_nanos(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_timeout_as_nanos.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_timeout_as_millis(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_timeout_as_millis.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_on_timeout(MemorySegment conn)
    {
        try
        {
            DowncallHandles.quiche_conn_on_timeout.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_close(MemorySegment conn, boolean app, long err, MemorySegment reason, long reason_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_close.invokeExact(conn, (byte)(app ? 1 : 0), err, reason, reason_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_trace_id(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_trace_id.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_source_id(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_source_id.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_source_ids(MemorySegment conn)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_source_ids.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_connection_id_iter_next(MemorySegment iter, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            return (byte)DowncallHandles.quiche_connection_id_iter_next.invokeExact(iter, out, out_len) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_connection_id_iter_free(MemorySegment iter)
    {
        try
        {
            DowncallHandles.quiche_connection_id_iter_free.invokeExact(iter);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_destination_id(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_destination_id.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_application_proto(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_application_proto.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_peer_cert(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_peer_cert.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_session(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            DowncallHandles.quiche_conn_session.invokeExact(conn, out, out_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_established(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_established.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_resumed(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_resumed.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_in_early_data(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_in_early_data.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_readable(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_readable.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_draining(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_draining.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_peer_streams_left_bidi(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_peer_streams_left_bidi.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_peer_streams_left_uni(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_peer_streams_left_uni.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_closed(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_closed.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_timed_out(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_timed_out.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_peer_error(MemorySegment conn, MemorySegment is_app, MemorySegment error_code, MemorySegment reason, MemorySegment reason_len)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_peer_error.invokeExact(conn, is_app, error_code, reason, reason_len) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_local_error(MemorySegment conn, MemorySegment is_app, MemorySegment error_code, MemorySegment reason, MemorySegment reason_len)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_local_error.invokeExact(conn, is_app, error_code, reason, reason_len) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_stream_iter_next(MemorySegment iter, MemorySegment stream_id)
    {
        try
        {
            return (byte)DowncallHandles.quiche_stream_iter_next.invokeExact(iter, stream_id) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_stream_iter_free(MemorySegment iter)
    {
        try
        {
            DowncallHandles.quiche_stream_iter_free.invokeExact(iter);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_stats(MemorySegment conn, MemorySegment out)
    {
        try
        {
            DowncallHandles.quiche_conn_stats.invokeExact(conn, out);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_peer_transport_params(MemorySegment conn, MemorySegment out)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_peer_transport_params.invokeExact(conn, out) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_path_stats(MemorySegment conn, long idx, MemorySegment out)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_path_stats.invokeExact(conn, idx, out);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_is_server(MemorySegment conn)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_is_server.invokeExact(conn) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send_ack_eliciting(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send_ack_eliciting.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_send_ack_eliciting_on_path(MemorySegment conn, MemorySegment local, int local_len, MemorySegment peer, int peer_len)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_send_ack_eliciting_on_path.invokeExact(conn, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_conn_retired_scid_next(MemorySegment conn, MemorySegment out, MemorySegment out_len)
    {
        try
        {
            return (byte)DowncallHandles.quiche_conn_retired_scid_next.invokeExact(conn, out, out_len) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_retired_scids(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_retired_scids.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_available_dcids(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_available_dcids.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_scids_left(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_scids_left.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_conn_active_scids(MemorySegment conn)
    {
        try
        {
            return (long)DowncallHandles.quiche_conn_active_scids.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_new_scid(MemorySegment conn, MemorySegment scid, long scid_len, MemorySegment reset_token, boolean retire_if_needed, MemorySegment scid_seq)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_new_scid.invokeExact(conn, scid, scid_len, reset_token, (byte)(retire_if_needed ? 1 : 0), scid_seq);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_probe_path(MemorySegment conn, MemorySegment local, int local_len, MemorySegment peer, int peer_len, MemorySegment seq)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_probe_path.invokeExact(conn, local, local_len, peer, peer_len, seq);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_migrate_source(MemorySegment conn, MemorySegment local, int local_len, MemorySegment seq)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_migrate_source.invokeExact(conn, local, local_len, seq);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_migrate(MemorySegment conn, MemorySegment local, int local_len, MemorySegment peer, int peer_len, MemorySegment seq)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_migrate.invokeExact(conn, local, local_len, peer, peer_len, seq);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_path_event_next(MemorySegment conn)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_path_event_next.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_path_event_type(MemorySegment ev)
    {
        try
        {
            return (int)DowncallHandles.quiche_path_event_type.invokeExact(ev);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_new(MemorySegment ev, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_new.invokeExact(ev, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_validated(MemorySegment ev, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_validated.invokeExact(ev, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_failed_validation(MemorySegment ev, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_failed_validation.invokeExact(ev, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_closed(MemorySegment ev, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_closed.invokeExact(ev, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_reused_source_connection_id(MemorySegment ev, MemorySegment id, MemorySegment old_local, MemorySegment old_local_len, MemorySegment old_peer, MemorySegment old_peer_len, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_reused_source_connection_id.invokeExact(ev, id, old_local, old_local_len, old_peer, old_peer_len, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_peer_migrated(MemorySegment ev, MemorySegment local, MemorySegment local_len, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            DowncallHandles.quiche_path_event_peer_migrated.invokeExact(ev, local, local_len, peer, peer_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_path_event_free(MemorySegment ev)
    {
        try
        {
            DowncallHandles.quiche_path_event_free.invokeExact(ev);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_retire_dcid(MemorySegment conn, long dcid_seq)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_retire_dcid.invokeExact(conn, dcid_seq);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static MemorySegment quiche_conn_paths_iter(MemorySegment conn, MemorySegment from, long from_len)
    {
        try
        {
            return (MemorySegment)DowncallHandles.quiche_conn_paths_iter.invokeExact(conn, from, from_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static boolean quiche_socket_addr_iter_next(MemorySegment iter, MemorySegment peer, MemorySegment peer_len)
    {
        try
        {
            return (byte)DowncallHandles.quiche_socket_addr_iter_next.invokeExact(iter, peer, peer_len) != 0;
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_socket_addr_iter_free(MemorySegment iter)
    {
        try
        {
            DowncallHandles.quiche_socket_addr_iter_free.invokeExact(iter);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_conn_is_path_validated(MemorySegment conn, MemorySegment from, long from_len, MemorySegment to, long to_len)
    {
        try
        {
            return (int)DowncallHandles.quiche_conn_is_path_validated.invokeExact(conn, from, from_len, to, to_len);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static void quiche_conn_free(MemorySegment conn)
    {
        try
        {
            DowncallHandles.quiche_conn_free.invokeExact(conn);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static int quiche_put_varint(MemorySegment buf, long buf_len, long val)
    {
        try
        {
            return (int)DowncallHandles.quiche_put_varint.invokeExact(buf, buf_len, val);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }

    public static long quiche_get_varint(MemorySegment buf, long buf_len, MemorySegment val)
    {
        try
        {
            return (long)DowncallHandles.quiche_get_varint.invokeExact(buf, buf_len, val);
        }
        catch (Throwable x)
        {
            throw new AssertionError("should not reach here", x);
        }
    }
}
