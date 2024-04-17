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

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.VarHandle;

import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_BOOL;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_LONG;

public class quiche_transport_params
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
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
        C_BOOL.withName("peer_disable_active_migration"),
        MemoryLayout.paddingLayout(7),
        C_LONG.withName("peer_active_conn_id_limit"),
        C_LONG.withName("peer_max_datagram_frame_size")
    );

    private static final VarHandle peer_initial_max_streams_bidi = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("peer_initial_max_streams_bidi"));

    public static MemorySegment allocate(SegmentAllocator scope)
    {
        return scope.allocate(LAYOUT);
    }

    public static long get_peer_initial_max_streams_bidi(MemorySegment quicheTransportParams)
    {
        return (long)peer_initial_max_streams_bidi.get(quicheTransportParams, 0L);
    }
}
