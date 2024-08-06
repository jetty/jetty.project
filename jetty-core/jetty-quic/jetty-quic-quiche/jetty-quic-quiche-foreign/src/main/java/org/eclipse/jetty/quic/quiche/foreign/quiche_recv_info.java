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
import java.net.SocketAddress;

import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_INT;
import static org.eclipse.jetty.quic.quiche.foreign.NativeHelper.C_POINTER;

public class quiche_recv_info
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_POINTER.withName("from"),
        C_INT.withName("from_len"),
        MemoryLayout.paddingLayout(4),
        C_POINTER.withName("to"),
        C_INT.withName("to_len"),
        MemoryLayout.paddingLayout(4)
    );

    private static final VarHandle from = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("from"));
    private static final VarHandle from_len = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("from_len"));
    private static final VarHandle to = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("to"));
    private static final VarHandle to_len = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("to_len"));

    public static MemorySegment allocate(SegmentAllocator scope)
    {
        return scope.allocate(LAYOUT);
    }

    public static void setSocketAddress(MemorySegment recvInfo, SocketAddress local, SocketAddress peer, SegmentAllocator scope)
    {
        MemorySegment peerSockAddrSegment = sockaddr.convert(peer, scope);
        from.set(recvInfo, 0L, peerSockAddrSegment);
        from_len.set(recvInfo, 0L, (int)peerSockAddrSegment.byteSize());
        MemorySegment localSockAddrSegment = sockaddr.convert(local, scope);
        to.set(recvInfo, 0L, localSockAddrSegment);
        to_len.set(recvInfo, 0L, (int)localSockAddrSegment.byteSize());
    }
}
