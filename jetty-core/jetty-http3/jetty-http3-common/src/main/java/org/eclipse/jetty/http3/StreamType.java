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

package org.eclipse.jetty.http3;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>The HTTP/3 stream type from
 * <a href="https://datatracker.ietf.org/doc/html/rfc9114#name-stream-types">RFC 9114</a>.</p>
 */
public enum StreamType
{
    CONTROL_STREAM(0x00),
    PUSH_STREAM(0x01),
    ENCODER_STREAM(0x02),
    DECODER_STREAM(0x03);

    public static StreamType from(long type)
    {
        return Types.types.get(type);
    }

    public static boolean isReserved(long streamType)
    {
        // SPEC: reserved stream types follow the formula: 0x1F * N + 0x21.
        return (streamType - 0x21) % 0x1F == 0;
    }

    private final long type;

    StreamType(long type)
    {
        this.type = type;
        Types.types.put(type, this);
    }

    public long type()
    {
        return type;
    }

    private static class Types
    {
        private static final Map<Long, StreamType> types = new HashMap<>();
    }
}
