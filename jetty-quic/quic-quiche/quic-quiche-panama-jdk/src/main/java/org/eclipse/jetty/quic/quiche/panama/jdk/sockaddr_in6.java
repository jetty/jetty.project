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

package org.eclipse.jetty.quic.quiche.panama.jdk;

import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_SHORT;

class sockaddr_in6
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_SHORT.withName("sin6_family"),
        C_SHORT.withName("sin6_port"),
        C_INT.withName("sin6_flowinfo"),
        MemoryLayout.sequenceLayout(16, C_CHAR).withName("sin6_addr"),
        C_INT.withName("sin6_scope_id")
    ).withName("sockaddr_in6");

    private static final VarHandle sin6_family = LAYOUT.varHandle(short.class, MemoryLayout.PathElement.groupElement("sin6_family"));
    private static final VarHandle sin6_port = LAYOUT.varHandle(short.class, MemoryLayout.PathElement.groupElement("sin6_port"));
    private static final VarHandle sin6_scope_id = LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("sin6_scope_id"));
    private static final VarHandle sin6_flowinfo = LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("sin6_flowinfo"));

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }

    public static void set_sin6_addr(MemorySegment sin6, byte[] value)
    {
        sin6.asSlice(8, 16).asByteBuffer().order(ByteOrder.nativeOrder()).put(value);
    }

    public static void set_sin6_family(MemorySegment sin6, short value)
    {
        sin6_family.set(sin6, value);
    }

    public static void set_sin6_port(MemorySegment sin6, short value)
    {
        sin6_port.set(sin6, value);
    }

    public static void set_sin6_scope_id(MemorySegment sin6, int value)
    {
        sin6_scope_id.set(sin6, value);
    }

    public static void set_sin6_flowinfo(MemorySegment sin6, int value)
    {
        sin6_flowinfo.set(sin6, value);
    }
}
