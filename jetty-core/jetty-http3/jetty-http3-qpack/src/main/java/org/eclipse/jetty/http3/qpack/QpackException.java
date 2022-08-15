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

package org.eclipse.jetty.http3.qpack;

public abstract class QpackException extends Exception
{
    public static final long QPACK_DECOMPRESSION_FAILED = 0x200;
    public static final long QPACK_ENCODER_STREAM_ERROR = 0x201;
    public static final long QPACK_DECODER_STREAM_ERROR = 0x202;
    public static final long H3_GENERAL_PROTOCOL_ERROR = 0x0101;
    private final long _errorCode;

    QpackException(long errorCode, String messageFormat, Throwable cause)
    {
        super(messageFormat, cause);
        _errorCode = errorCode;
    }

    public long getErrorCode()
    {
        return _errorCode;
    }

    /**
     * A Stream QPACK exception.
     * <p>Stream exceptions are not fatal to the connection and the
     * qpack state is complete and able to continue handling other
     * decoding/encoding for the session.
     * </p>
     */
    public static class StreamException extends QpackException
    {
        public StreamException(long errorCode, String messageFormat)
        {
            this(errorCode, messageFormat, null);
        }

        public StreamException(long errorCode, String messageFormat, Throwable cause)
        {
            super(errorCode, messageFormat, cause);
        }
    }

    /**
     * A Session QPACK Exception.
     * <p>Session exceptions are fatal for the stream and the QPACK
     * state is unable to decode/encode further. </p>
     */
    public static class SessionException extends QpackException
    {
        public SessionException(long errorCode, String message)
        {
            this(errorCode, message, null);
        }

        public SessionException(long errorCode, String message, Throwable cause)
        {
            super(errorCode, message, cause);
        }
    }
}
