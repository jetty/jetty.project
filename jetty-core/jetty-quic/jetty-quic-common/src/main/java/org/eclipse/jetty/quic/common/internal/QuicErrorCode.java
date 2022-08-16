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

package org.eclipse.jetty.quic.common.internal;

public enum QuicErrorCode
{
    NO_ERROR(0x00),
    INTERNAL_ERROR(0x01),
    CONNECTION_REFUSED(0x02),
    FLOW_CONTROL_ERROR(0x03),
    STREAM_LIMIT_ERROR(0x04),
    STREAM_STATE_ERROR(0x05),
    FINAL_SIZE_ERROR(0x06),
    FRAME_ENCODING_ERROR(0x07),
    TRANSPORT_PARAMETER_ERROR(0x08),
    CONNECTION_ID_LIMIT_ERROR(0x09),
    PROTOCOL_VIOLATION(0x0A),
    INVALID_TOKEN(0x0B),
    APPLICATION_ERROR(0x0C),
    CRYPTO_BUFFER_EXCEEDED(0x0D),
    KEY_UPDATE_ERROR(0x0E),
    AEAD_LIMIT_REACHED(0x0F),
    NO_VIABLE_PATH(0x10);

    private final long code;

    QuicErrorCode(long code)
    {
        this.code = code;
    }

    public long code()
    {
        return code;
    }
}
