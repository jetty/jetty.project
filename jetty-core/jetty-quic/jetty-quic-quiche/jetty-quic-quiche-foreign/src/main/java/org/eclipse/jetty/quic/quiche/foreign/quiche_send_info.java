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

import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_INT;

public class quiche_send_info
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        sockaddr_storage.layout().withName("from"),
        C_INT.withName("from_len"),
        MemoryLayout.paddingLayout(4),
        sockaddr_storage.layout().withName("to"),
        C_INT.withName("to_len"),
        MemoryLayout.paddingLayout(4),
        timespec.layout().withName("at")
    );

    public static MemorySegment allocate(SegmentAllocator scope)
    {
        return scope.allocate(LAYOUT);
    }
}
