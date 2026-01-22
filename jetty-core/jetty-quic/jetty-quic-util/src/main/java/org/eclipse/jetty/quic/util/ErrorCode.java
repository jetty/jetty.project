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

package org.eclipse.jetty.quic.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ErrorCode
{
    public static final ErrorCode NO_ERROR = new ErrorCode(0x00);
    public static final ErrorCode INTERNAL_ERROR = new ErrorCode(0x01);
    public static final ErrorCode CONNECTION_REFUSED_ERROR = new ErrorCode(0x02);
    public static final ErrorCode FLOW_CONTROL_ERROR = new ErrorCode(0x03);
    public static final ErrorCode STREAM_LIMIT_ERROR = new ErrorCode(0x04);
    public static final ErrorCode STREAM_STATE_ERROR = new ErrorCode(0x05);
    public static final ErrorCode FINAL_SIZE_ERROR = new ErrorCode(0x06);
    public static final ErrorCode FRAME_ENCODING_ERROR = new ErrorCode(0x07);
    public static final ErrorCode TRANSPORT_PARAMETER_ERROR = new ErrorCode(0x08);
    public static final ErrorCode CONNECTION_ID_LIMIT_ERROR = new ErrorCode(0x09);
    public static final ErrorCode PROTOCOL_VIOLATION_ERROR = new ErrorCode(0x0A);
    public static final ErrorCode INVALID_TOKEN_ERROR = new ErrorCode(0x0B);
    public static final ErrorCode APPLICATION_ERROR = new ErrorCode(0x0C);
    public static final ErrorCode CRYPTO_BUFFER_EXCEEDED_ERROR = new ErrorCode(0x0D);
    public static final ErrorCode KEY_UPDATE_ERROR = new ErrorCode(0x0E);
    public static final ErrorCode AEAD_LIMIT_REACHED_ERROR = new ErrorCode(0x0F);
    public static final ErrorCode NO_VIABLE_PATH_ERROR = new ErrorCode(0x10);
    public static final ErrorCode CRYPTO_ERROR = new ErrorCode(0x100);

    private final long code;

    private ErrorCode(long code)
    {
        this.code = code;
        Codes.codes.putIfAbsent(code, this);
    }

    public long code()
    {
        return code;
    }

    public boolean isCrypto()
    {
        return isCrypto(code);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof ErrorCode errorCode)
            return code == errorCode.code;
        return false;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(code);
    }

    @Override
    public String toString()
    {
        return "%s[0x%x]".formatted(getClass().getSimpleName(), code);
    }

    private static boolean isCrypto(long code)
    {
        return 0x100 <= code && code <= 0x1FF;
    }

    public static ErrorCode from(long code)
    {
        ErrorCode result = Codes.codes.get(code);
        if (result == null && isCrypto(code))
            result = new ErrorCode(code);
        return result;
    }

    private static class Codes
    {
        private static final Map<Long, ErrorCode> codes = new ConcurrentHashMap<>();
    }
}
