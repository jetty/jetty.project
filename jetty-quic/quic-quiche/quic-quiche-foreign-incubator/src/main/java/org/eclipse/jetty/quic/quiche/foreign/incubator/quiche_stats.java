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

import java.lang.invoke.VarHandle;

import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_LONG;

public class quiche_stats
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_LONG.withName("recv"),
        C_LONG.withName("sent"),
        C_LONG.withName("lost"),
        C_LONG.withName("retrans"),
        C_LONG.withName("rtt"),
        C_LONG.withName("cwnd"),
        C_LONG.withName("sent_bytes"),
        C_LONG.withName("recv_bytes"),
        C_LONG.withName("lost_bytes"),
        C_LONG.withName("stream_retrans_bytes"),
        C_LONG.withName("pmtu"),
        C_LONG.withName("delivery_rate"),
        C_LONG.withName("peer_max_idle_timeout"),
        C_LONG.withName("peer_max_udp_payload_size"),
        C_LONG.withName("peer_initial_max_data"),
        C_LONG.withName("peer_initial_max_stream_data_bidi_local"),
        C_LONG.withName("peer_initial_max_stream_data_bidi_remote"),
        C_LONG.withName("peer_initial_max_stream_data_uni"),
        C_LONG.withName("peer_initial_max_streams_bidi"),
        C_LONG.withName("peer_initial_max_streams_uni"),
        C_LONG.withName("peer_ack_delay_exponent"),
        C_LONG.withName("peer_max_ack_delay"),
        C_CHAR.withName("peer_disable_active_migration"),
        MemoryLayout.paddingLayout(56),
        C_LONG.withName("peer_active_conn_id_limit"),
        C_LONG.withName("peer_max_datagram_frame_size")
    );

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }

    private static final VarHandle cwnd = LAYOUT.varHandle(long.class, MemoryLayout.PathElement.groupElement("cwnd"));
    private static final VarHandle peer_initial_max_streams_bidi = LAYOUT.varHandle(long.class, MemoryLayout.PathElement.groupElement("peer_initial_max_streams_bidi"));

    public static long get_cwnd(MemorySegment stats)
    {
        return (long)cwnd.get(stats);
    }

    public static long get_peer_initial_max_streams_bidi(MemorySegment stats)
    {
        return (long)peer_initial_max_streams_bidi.get(stats);
    }
}
