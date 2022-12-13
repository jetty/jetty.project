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

package org.eclipse.jetty.quic.common;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>The QUIC stream type, either client or server initiated, and either unidirectional or bidirectional.</p>
 */
public enum StreamType
{
    CLIENT_BIDIRECTIONAL(0x00),
    SERVER_BIDIRECTIONAL(0x01),
    CLIENT_UNIDIRECTIONAL(0x02),
    SERVER_UNIDIRECTIONAL(0x03);

    public static StreamType from(long streamId)
    {
        int type = ((int)(streamId)) & 0b11;
        return Types.types.get(type);
    }

    public static boolean isReserved(long streamType)
    {
        // SPEC: reserved stream types follow the formula: 0x1F * N + 0x21.
        return (streamType - 0x21) % 0x1F == 0;
    }

    private final int type;

    private StreamType(int type)
    {
        this.type = type;
        Types.types.put(type, this);
    }

    public int type()
    {
        return type;
    }

    private static class Types
    {
        private static final Map<Integer, StreamType> types = new HashMap<>();
    }
}
