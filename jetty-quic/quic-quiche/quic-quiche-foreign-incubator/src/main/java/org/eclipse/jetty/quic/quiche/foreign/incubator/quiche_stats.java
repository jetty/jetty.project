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

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_LONG;

public class quiche_stats
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_LONG.withName("recv"),
        C_LONG.withName("sent"),
        C_LONG.withName("lost"),
        C_LONG.withName("retrans"),
        C_LONG.withName("sent_bytes"),
        C_LONG.withName("recv_bytes"),
        C_LONG.withName("lost_bytes"),
        C_LONG.withName("stream_retrans_bytes"),
        C_LONG.withName("paths_count"),
        C_LONG.withName("reset_stream_count_local"),
        C_LONG.withName("stopped_stream_count_local"),
        C_LONG.withName("reset_stream_count_remote"),
        C_LONG.withName("stopped_stream_count_remote")
    );

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }

}
