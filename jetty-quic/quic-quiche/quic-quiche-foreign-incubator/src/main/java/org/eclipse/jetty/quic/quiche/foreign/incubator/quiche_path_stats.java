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
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_LONG;
import static jdk.incubator.foreign.CLinker.C_SHORT;

public class quiche_path_stats
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.structLayout( // struct sockaddr_storage
            C_SHORT.withName("ss_family"),
            MemoryLayout.sequenceLayout(118, C_CHAR).withName("__ss_padding"),
            C_LONG.withName("__ss_align")
        ).withName("local_addr"),
        C_INT.withName("local_addr_len"),
        MemoryLayout.paddingLayout(32),
        MemoryLayout.structLayout( // struct sockaddr_storage
            C_SHORT.withName("ss_family"),
            MemoryLayout.sequenceLayout(118, C_CHAR).withName("__ss_padding"),
            C_LONG.withName("__ss_align")
        ).withName("peer_addr"),
        C_INT.withName("peer_addr_len"),
        MemoryLayout.paddingLayout(32),
        C_LONG.withName("validation_state"),
        C_CHAR.withName("active"),
        MemoryLayout.paddingLayout(56),
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

    private static final VarHandle cwnd = LAYOUT.varHandle(long.class, MemoryLayout.PathElement.groupElement("cwnd"));

    public static long get_cwnd(MemorySegment stats)
    {
        return (long)cwnd.get(stats);
    }

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }
}
