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

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.ThreadLocalRandom;

public enum HTTP3ErrorCode
{
    NO_ERROR(0x100),
    PROTOCOL_ERROR(0x101),
    INTERNAL_ERROR(0x102),
    STREAM_CREATION_ERROR(0x103),
    CLOSED_CRITICAL_STREAM_ERROR(0x104),
    FRAME_UNEXPECTED_ERROR(0x105),
    FRAME_ERROR(0x106),
    EXCESSIVE_LOAD_ERROR(0x107),
    ID_ERROR(0x108),
    SETTINGS_ERROR(0x109),
    MISSING_SETTINGS_ERROR(0x10A),
    REQUEST_REJECTED_ERROR(0x10B),
    REQUEST_CANCELLED_ERROR(0x10C),
    REQUEST_INCOMPLETE_ERROR(0x10D),
    HTTP_MESSAGE_ERROR(0x10E),
    HTTP_CONNECT_ERROR(0x10F),
    VERSION_FALLBACK_ERROR(0x110);

    private final long code;

    HTTP3ErrorCode(long code)
    {
        this.code = code;
    }

    public static long randomReservedCode()
    {
        // SPEC: reserved errors have the form 0x1F * n + 0x21.
        // This constant avoids to overflow VarLenInt, which is how an error code is encoded.
        long n = ThreadLocalRandom.current().nextLong(0x210842108421084L);
        return 0x1F * n + 0x21;
    }

    public long code()
    {
        return code;
    }
}
