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

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import java.lang.invoke.MethodHandle;

import jdk.incubator.foreign.Addressable;
import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.ResourceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_LONG;
import static jdk.incubator.foreign.CLinker.C_POINTER;
import static org.eclipse.jetty.quic.quiche.foreign.incubator.NativeHelper.downcallHandle;

public class quiche_h
{
    // This interface is a translation of the quiche.h header of a specific version.
    // It needs to be reviewed each time the native lib version changes.
    private static final String EXPECTED_QUICHE_VERSION = "0.16.0";

    public static final byte C_FALSE = 0;
    public static final byte C_TRUE = 1;

    private static final Logger LOG = LoggerFactory.getLogger(quiche_h.class);

    private static final MethodHandle quiche_config_new$MH = downcallHandle(
        "quiche_config_new",
        "(I)Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER, C_INT)
    );

    private static final MethodHandle quiche_config_set_cc_algorithm$MH = downcallHandle(
        "quiche_config_set_cc_algorithm",
        "(Ljdk/incubator/foreign/MemoryAddress;I)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_INT)
    );

    private static final MethodHandle quiche_config_load_cert_chain_from_pem_file$MH = downcallHandle(
        "quiche_config_load_cert_chain_from_pem_file",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_config_load_priv_key_from_pem_file$MH = downcallHandle(
        "quiche_config_load_priv_key_from_pem_file",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_config_verify_peer$MH = downcallHandle(
        "quiche_config_verify_peer",
        "(Ljdk/incubator/foreign/MemoryAddress;B)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_CHAR)
    );

    private static final MethodHandle quiche_config_set_application_protos$MH = downcallHandle(
        "quiche_config_set_application_protos",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;J)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_max_idle_timeout$MH = downcallHandle(
        "quiche_config_set_max_idle_timeout",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_max_send_udp_payload_size$MH = downcallHandle(
        "quiche_config_set_max_send_udp_payload_size",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_data$MH = downcallHandle(
        "quiche_config_set_initial_max_data",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_stream_data_bidi_local$MH = downcallHandle(
        "quiche_config_set_initial_max_stream_data_bidi_local",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_stream_data_bidi_remote$MH = downcallHandle(
        "quiche_config_set_initial_max_stream_data_bidi_remote",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_stream_data_uni$MH = downcallHandle(
        "quiche_config_set_initial_max_stream_data_uni",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_streams_bidi$MH = downcallHandle(
        "quiche_config_set_initial_max_streams_bidi",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_initial_max_streams_uni$MH = downcallHandle(
        "quiche_config_set_initial_max_streams_uni",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_ack_delay_exponent$MH = downcallHandle(
        "quiche_config_set_ack_delay_exponent",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_max_ack_delay$MH = downcallHandle(
        "quiche_config_set_max_ack_delay",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_disable_active_migration$MH = downcallHandle(
        "quiche_config_set_disable_active_migration",
        "(Ljdk/incubator/foreign/MemoryAddress;B)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_CHAR)
    );

    private static final MethodHandle quiche_config_set_max_connection_window$MH = downcallHandle(
        "quiche_config_set_max_connection_window",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_max_stream_window$MH = downcallHandle(
        "quiche_config_set_max_stream_window",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_set_active_connection_id_limit$MH = downcallHandle(
        "quiche_config_set_active_connection_id_limit",
        "(Ljdk/incubator/foreign/MemoryAddress;J)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_config_free$MH = downcallHandle(
        "quiche_config_free",
        "(Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER)
    );

    private static final MethodHandle quiche_connect$MH = downcallHandle(
        "quiche_connect",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_conn_send$MH = downcallHandle(
        "quiche_conn_send",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_header_info$MH = downcallHandle(
        "quiche_header_info",
        "(Ljdk/incubator/foreign/MemoryAddress;JJLjdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_LONG, C_LONG, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_version_is_supported$MH = downcallHandle(
        "quiche_version_is_supported",
        "(I)B",
        FunctionDescriptor.of(C_CHAR, C_INT)
    );

    private static final MethodHandle quiche_accept$MH = downcallHandle(
        "quiche_accept",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_negotiate_version$MH = downcallHandle(
        "quiche_negotiate_version",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;J)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_retry$MH = downcallHandle(
        "quiche_retry",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JILjdk/incubator/foreign/MemoryAddress;J)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_INT, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_conn_recv$MH = downcallHandle(
        "quiche_conn_recv",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_conn_is_established$MH = downcallHandle(
        "quiche_conn_is_established",
        "(Ljdk/incubator/foreign/MemoryAddress;)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER)
    );

    private static final MethodHandle quiche_conn_application_proto$MH = downcallHandle(
        "quiche_conn_application_proto",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_conn_is_closed$MH = downcallHandle(
        "quiche_conn_is_closed",
        "(Ljdk/incubator/foreign/MemoryAddress;)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER)
    );

    private static final MethodHandle quiche_conn_timeout_as_millis$MH = downcallHandle(
        "quiche_conn_timeout_as_millis",
        "(Ljdk/incubator/foreign/MemoryAddress;)J",
        FunctionDescriptor.of(C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_conn_on_timeout$MH = downcallHandle(
        "quiche_conn_on_timeout",
        "(Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER)
    );

    private static final MethodHandle quiche_conn_is_draining$MH = downcallHandle(
        "quiche_conn_is_draining",
        "(Ljdk/incubator/foreign/MemoryAddress;)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER)
    );

    private static final MethodHandle quiche_conn_peer_error$MH = downcallHandle(
        "quiche_conn_peer_error",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_conn_stats$MH = downcallHandle(
        "quiche_conn_stats",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_conn_path_stats$MH = downcallHandle(
        "quiche_conn_path_stats",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_conn_stream_finished$MH = downcallHandle(
        "quiche_conn_stream_finished",
        "(Ljdk/incubator/foreign/MemoryAddress;J)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_conn_stream_shutdown$MH = downcallHandle(
        "quiche_conn_stream_shutdown",
        "(Ljdk/incubator/foreign/MemoryAddress;JIJ)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_LONG, C_INT, C_LONG)
    );

    private static final MethodHandle quiche_conn_stream_capacity$MH = downcallHandle(
        "quiche_conn_stream_capacity",
        "(Ljdk/incubator/foreign/MemoryAddress;J)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_conn_stream_send$MH = downcallHandle(
        "quiche_conn_stream_send",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JB)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_CHAR)
    );

    private static final MethodHandle quiche_conn_stream_recv$MH = downcallHandle(
        "quiche_conn_stream_recv",
        "(Ljdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;JLjdk/incubator/foreign/MemoryAddress;)J",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_LONG, C_POINTER, C_LONG, C_POINTER)
    );

    private static final MethodHandle quiche_stream_iter_next$MH = downcallHandle(
        "quiche_stream_iter_next",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)B",
        FunctionDescriptor.of(C_CHAR, C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_stream_iter_free$MH = downcallHandle(
        "quiche_stream_iter_free",
        "(Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER)
    );

    private static final MethodHandle quiche_conn_readable$MH = downcallHandle(
        "quiche_conn_readable",
        "(Ljdk/incubator/foreign/MemoryAddress;)Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_conn_writable$MH = downcallHandle(
        "quiche_conn_writable",
        "(Ljdk/incubator/foreign/MemoryAddress;)Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER, C_POINTER)
    );

    private static final MethodHandle quiche_conn_close$MH = downcallHandle(
        "quiche_conn_close",
        "(Ljdk/incubator/foreign/MemoryAddress;BJLjdk/incubator/foreign/MemoryAddress;J)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_CHAR, C_LONG, C_POINTER, C_LONG)
    );

    private static final MethodHandle quiche_conn_free$MH = downcallHandle(
        "quiche_conn_free",
        "(Ljdk/incubator/foreign/MemoryAddress;)V",
        FunctionDescriptor.ofVoid(C_POINTER)
    );

    private static final MethodHandle quiche_version$MH = downcallHandle(
        "quiche_version",
        "()Ljdk/incubator/foreign/MemoryAddress;",
        FunctionDescriptor.of(C_POINTER)
    );

    private static final MethodHandle quiche_enable_debug_logging$MH = downcallHandle(
        "quiche_enable_debug_logging",
        "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)I",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER)
    );

    public interface quiche_shutdown
    {
        int QUICHE_SHUTDOWN_READ = 0,
            QUICHE_SHUTDOWN_WRITE = 1;
    }

    public static MemoryAddress quiche_config_new(int version)
    {
        try
        {
            return (MemoryAddress) quiche_config_new$MH.invokeExact(version);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_config_load_cert_chain_from_pem_file(MemoryAddress config, MemoryAddress path)
    {
        try
        {
            return (int) quiche_config_load_cert_chain_from_pem_file$MH.invokeExact(config, path);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_config_load_priv_key_from_pem_file(MemoryAddress config, MemoryAddress path)
    {
        try
        {
            return (int) quiche_config_load_priv_key_from_pem_file$MH.invokeExact(config, path);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_ack_delay_exponent(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_ack_delay_exponent$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_config_set_application_protos(MemoryAddress config, MemoryAddress protos, long protos_len)
    {
        try
        {
            return (int) quiche_config_set_application_protos$MH.invokeExact(config, protos, protos_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_cc_algorithm(MemoryAddress config, int algo)
    {
        try
        {
            quiche_config_set_cc_algorithm$MH.invokeExact(config, algo);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_disable_active_migration(MemoryAddress config, byte v)
    {
        try
        {
            quiche_config_set_disable_active_migration$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_max_connection_window(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_max_connection_window$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_max_stream_window(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_max_stream_window$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_active_connection_id_limit(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_active_connection_id_limit$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_data(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_data$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_bidi_local(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_stream_data_bidi_local$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_bidi_remote(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_stream_data_bidi_remote$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_stream_data_uni(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_stream_data_uni$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_streams_bidi(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_streams_bidi$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_initial_max_streams_uni(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_initial_max_streams_uni$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_max_ack_delay(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_max_ack_delay$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_max_idle_timeout(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_max_idle_timeout$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_set_max_send_udp_payload_size(MemoryAddress config, long v)
    {
        try
        {
            quiche_config_set_max_send_udp_payload_size$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_verify_peer(MemoryAddress config, byte v)
    {
        try
        {
            quiche_config_verify_peer$MH.invokeExact(config, v);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_config_free(MemoryAddress config)
    {
        try
        {
            quiche_config_free$MH.invokeExact(config.address());
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static MemoryAddress quiche_connect(Addressable server_name, Addressable scid, long scid_len, Addressable local, long local_len, Addressable peer, long peer_len, Addressable config)
    {
        try
        {
            return (MemoryAddress) quiche_connect$MH.invokeExact(server_name.address(), scid.address(), scid_len, local.address(), local_len, peer.address(), peer_len, config.address());
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_stream_recv(MemoryAddress conn, long stream_id, MemoryAddress buf, long buf_len, MemoryAddress fin)
    {
        try
        {
            return (long) quiche_conn_stream_recv$MH.invokeExact(conn, stream_id, buf, buf_len, fin);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_stream_send(MemoryAddress conn, long stream_id, MemoryAddress buf, long buf_len, byte fin)
    {
        try
        {
            return (long) quiche_conn_stream_send$MH.invokeExact(conn, stream_id, buf, buf_len, fin);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_stream_iter_next(MemoryAddress conn, MemoryAddress stream_id)
    {
        try
        {
            return (byte) quiche_stream_iter_next$MH.invokeExact(conn, stream_id);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_stream_iter_free(MemoryAddress conn)
    {
        try
        {
            quiche_stream_iter_free$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static MemoryAddress quiche_conn_readable(MemoryAddress conn)
    {
        try
        {
            return (MemoryAddress) quiche_conn_readable$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static MemoryAddress quiche_conn_writable(MemoryAddress conn)
    {
        try
        {
            return (MemoryAddress) quiche_conn_writable$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_conn_peer_error(MemoryAddress conn, MemoryAddress is_app, MemoryAddress error_code, MemoryAddress reason, MemoryAddress reason_len)
    {
        try
        {
            return (byte) quiche_conn_peer_error$MH.invokeExact(conn, is_app, error_code, reason, reason_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_stream_capacity(MemoryAddress conn, long stream_id)
    {
        try
        {
            return (long) quiche_conn_stream_capacity$MH.invokeExact(conn, stream_id);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_conn_stream_shutdown(MemoryAddress conn, long stream_id, int direction, long err)
    {
        try
        {
            return (int) quiche_conn_stream_shutdown$MH.invokeExact(conn, stream_id, direction, err);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_conn_close(MemoryAddress conn, byte app, long err, MemoryAddress reason, long reason_len)
    {
        try
        {
            return (int) quiche_conn_close$MH.invokeExact(conn, app, err, reason, reason_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_conn_stream_finished(MemoryAddress conn, long stream_id)
    {
        try
        {
            return (byte) quiche_conn_stream_finished$MH.invokeExact(conn, stream_id);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_conn_stats(MemoryAddress conn, MemoryAddress stats)
    {
        try
        {
            quiche_conn_stats$MH.invokeExact(conn, stats);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_conn_path_stats(MemoryAddress conn, long idx, MemoryAddress stats)
    {
        try
        {
            return (int)quiche_conn_path_stats$MH.invokeExact(conn, idx, stats);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_conn_on_timeout(MemoryAddress conn)
    {
        try
        {
            quiche_conn_on_timeout$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_conn_is_draining(MemoryAddress conn)
    {
        try
        {
            return (byte)quiche_conn_is_draining$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_timeout_as_millis(MemoryAddress conn)
    {
        try
        {
            return (long)quiche_conn_timeout_as_millis$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_conn_is_closed(MemoryAddress conn)
    {
        try
        {
            return (byte)quiche_conn_is_closed$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_conn_application_proto(MemoryAddress conn, MemoryAddress out, MemoryAddress out_len)
    {
        try
        {
            quiche_conn_application_proto$MH.invokeExact(conn, out, out_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_conn_is_established(MemoryAddress conn)
    {
        try
        {
            return (byte)quiche_conn_is_established$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_recv(MemoryAddress conn, MemoryAddress buf, long buf_len, MemoryAddress info)
    {
        try
        {
            return (long)quiche_conn_recv$MH.invokeExact(conn, buf, buf_len, info);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_retry(MemoryAddress scid, long scid_len, MemoryAddress dcid, long dcid_len, MemoryAddress new_scid, long new_scid_len, MemoryAddress token, long token_len, int version, MemoryAddress out, long out_len)
    {
        try
        {
            return (long)quiche_retry$MH.invokeExact(scid, scid_len, dcid, dcid_len, new_scid, new_scid_len, token, token_len, version, out, out_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_negotiate_version(MemoryAddress scid, long scid_len, MemoryAddress dcid, long dcid_len, MemoryAddress out, long out_len)
    {
        try
        {
            return (long)quiche_negotiate_version$MH.invokeExact(scid, scid_len, dcid, dcid_len, out, out_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static byte quiche_version_is_supported(int version)
    {
        try
        {
            return (byte)quiche_version_is_supported$MH.invokeExact(version);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static MemoryAddress quiche_accept(MemoryAddress scid, long scid_len,
                                              MemoryAddress odcid, long odcid_len,
                                              MemoryAddress local, long local_len,
                                              MemoryAddress peer, long peer_len,
                                              MemoryAddress config)
    {
        try
        {
            return (MemoryAddress)quiche_accept$MH.invokeExact(scid, scid_len, odcid, odcid_len, local, local_len, peer, peer_len, config);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static int quiche_header_info(MemoryAddress buf, long buf_len, long dcil,
                                         MemoryAddress version, MemoryAddress type,
                                         MemoryAddress scid, MemoryAddress scid_len,
                                         MemoryAddress dcid, MemoryAddress dcid_len,
                                         MemoryAddress token, MemoryAddress token_len)
    {
        try
        {
            return (int)quiche_header_info$MH.invokeExact(buf, buf_len, dcil, version, type, scid, scid_len, dcid, dcid_len, token, token_len);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static long quiche_conn_send(MemoryAddress conn, MemoryAddress out, long out_len, MemoryAddress out_info)
    {
        try
        {
            return (long)quiche_conn_send$MH.invokeExact(conn, out, out_len, out_info);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static void quiche_conn_free(MemoryAddress conn)
    {
        try
        {
            quiche_conn_free$MH.invokeExact(conn);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    public static MemoryAddress quiche_version()
    {
        try
        {
            return (MemoryAddress) quiche_version$MH.invokeExact();
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    private static int quiche_enable_debug_logging(MemoryAddress cb, MemoryAddress argp)
    {
        try
        {
            return (int)quiche_enable_debug_logging$MH.invokeExact(cb, argp);
        }
        catch (Throwable ex)
        {
            throw new AssertionError("should not reach here", ex);
        }
    }

    private static class LoggingCallback
    {
        private static final LoggingCallback INSTANCE = new LoggingCallback();
        private static final ResourceScope SCOPE = ResourceScope.newImplicitScope();

        public void log(MemoryAddress msg, MemoryAddress argp)
        {
            LOG.debug(CLinker.toJavaString(msg));
        }
    }

    static
    {
        String quicheVersion = CLinker.toJavaString(quiche_version());
        if (!EXPECTED_QUICHE_VERSION.equals(quicheVersion))
            throw new IllegalStateException("Native Quiche library version [" + quicheVersion + "] does not match expected version [" + EXPECTED_QUICHE_VERSION + "]");

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Quiche version {}", quicheVersion);

            MemoryAddress cb = NativeHelper.upcallHandle(LoggingCallback.class, LoggingCallback.INSTANCE,
                "log", "(Ljdk/incubator/foreign/MemoryAddress;Ljdk/incubator/foreign/MemoryAddress;)V",
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER), LoggingCallback.SCOPE);
            if (quiche_enable_debug_logging(cb, MemoryAddress.NULL) != 0)
                throw new AssertionError("Cannot enable quiche debug logging");
        }
    }
}
