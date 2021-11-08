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

import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_SHORT;

class sockaddr_in
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        C_SHORT.withName("sin_family"),
        C_SHORT.withName("sin_port"),
        C_INT.withName("sin_addr"),
        MemoryLayout.sequenceLayout(8, C_CHAR).withName("sin_zero")
    ).withName("sockaddr_in");

    private static final VarHandle sin_family = LAYOUT.varHandle(short.class, MemoryLayout.PathElement.groupElement("sin_family"));
    private static final VarHandle sin_port = LAYOUT.varHandle(short.class, MemoryLayout.PathElement.groupElement("sin_port"));
    private static final VarHandle sin_addr = LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("sin_addr"));

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }

    public static void set_sin_family(MemorySegment sin, short value)
    {
        sin_family.set(sin, value);
    }

    public static void set_sin_port(MemorySegment sin, short value)
    {
        sin_port.set(sin, value);
    }

    public static void set_sin_addr(MemorySegment sin, int value)
    {
        sin_addr.set(sin, value);
    }
}


