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
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_INT;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_LONG;

public class quiche_path_stats
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        sockaddr_storage.layout().withName("local_addr"),
        C_INT.withName("local_addr_len"),
        MemoryLayout.paddingLayout(4),
        sockaddr_storage.layout().withName("peer_addr"),
        C_INT.withName("peer_addr_len"),
        MemoryLayout.paddingLayout(4),
        C_LONG.withName("validation_state"),
        C_BOOL.withName("active"),
        MemoryLayout.paddingLayout(7),
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
        C_LONG.withName("delivery_rate")
    );

    private static final VarHandle cwnd = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cwnd"));

    public static long get_cwnd(MemorySegment stats)
    {
        return (long)cwnd.get(stats, 0L);
    }

    public static MemorySegment allocate(SegmentAllocator scope)
    {
        return scope.allocate(LAYOUT);
    }
}
