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

package org.eclipse.jetty.quic.common.frames;

import java.util.HashMap;
import java.util.Map;

public enum FrameType
{
    PADDING(0x00),
    PING(0x01),
    ACK(0x02, 0x03),
    RESET_STREAM(0x04),
    STOP_SENDING(0x05),
    CRYPTO(0x06),
    NEW_TOKEN(0x07),
    STREAM(0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F),
    MAX_DATA(0x10),
    STREAM_MAX_DATA(0x11),
    MAX_STREAMS(0x12, 0x13),
    DATA_BLOCKED(0x14),
    STREAM_DATA_BLOCKED(0x15),
    STREAMS_BLOCKED(0x16, 0x17),
    NEW_CONNECTION_ID(0x18),
    RETIRE_CONNECTION_ID(0x19),
    PATH_CHALLENGE(0x1A),
    PATH_RESPONSE(0x1B),
    CONNECTION_CLOSE(0x1C, 0x1D),
    HANDSHAKE_DONE(0x1E);

    public static FrameType from(long code)
    {
        return Codes.CODES.get(code);
    }

    FrameType(long... codes)
    {
        for (long code : codes)
        {
            Codes.CODES.put(code, this);
        }
    }

    private static class Codes
    {
        private static final Map<Long, FrameType> CODES = new HashMap<>();
    }
}
