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

import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_LONG;
import static jdk.incubator.foreign.CLinker.C_SHORT;

public class quiche_send_info
{
    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout( // struct sockaddr_storage
        MemoryLayout.structLayout(
            C_SHORT.withName("ss_family"),
            MemoryLayout.sequenceLayout(118, C_CHAR).withName("__ss_padding"),
            C_LONG.withName("__ss_align")
        ).withName("to"),
        C_INT.withName("to_len"),
        MemoryLayout.paddingLayout(32),
        MemoryLayout.structLayout( // struct timespec
            C_LONG.withName("tv_sec"),
            C_LONG.withName("tv_nsec")
        ).withName("at")
    );

    public static MemorySegment allocate(ResourceScope scope)
    {
        return MemorySegment.allocateNative(LAYOUT, scope);
    }
}
