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

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import java.lang.invoke.VarHandle;
import java.net.SocketAddress;

import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_POINTER;

public class quiche_recv_info
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_POINTER.withName("from"),
        C_INT.withName("from_len"),
        MemoryLayout.paddingLayout(32)
    );

    private static final VarHandle from = MemoryHandles.asAddressVarHandle(LAYOUT.varHandle(long.class, MemoryLayout.PathElement.groupElement("from")));
    private static final VarHandle from_len = LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("from_len"));

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }

    public static void setSocketAddress(MemorySegment recvInfo, SocketAddress peer, ResourceScope scope)
    {
        MemorySegment sockAddrSegment = sockaddr.convert(peer, scope);
        from.set(recvInfo, sockAddrSegment.address());
        from_len.set(recvInfo, (int)sockAddrSegment.byteSize());
    }
}
