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

import org.eclipse.jetty.util.TypeUtil;

/**
 * A QUIC exception carrying the error code, the error reason, and the frame type that caused the error.
 */
public class QuicException extends RuntimeException
{
    private final ErrorCode errorCode;
    private final long frameType;

    /**
     * Creates a new instance with the given error code and reason, and frame type {@code 0x00}.
     *
     * @param code the error code
     * @param reason the error reason
     */
    public QuicException(ErrorCode code, String reason)
    {
        this(code, reason, 0x00);
    }

    /**
     * Creates a new instance with the given error code, reason, and frame type.
     *
     * @param code the error code
     * @param reason the error reason
     * @param frameType the frame type that caused the error
     */
    public QuicException(ErrorCode code, String reason, long frameType)
    {
        super(reason);
        this.errorCode = code;
        this.frameType = frameType;
    }

    /**
     * Creates a new instance with the given error code, reason, frame type and nested cause.
     *
     * @param code the error code
     * @param reason the error reason
     * @param frameType the frame type that caused the error
     * @param cause
     */
    public QuicException(ErrorCode code, String reason, long frameType, Throwable cause)
    {
        super(reason, cause);
        this.errorCode = code;
        this.frameType = frameType;
    }

    /**
     * @return the error code
     */
    public ErrorCode getErrorCode()
    {
        return errorCode;
    }

    /**
     * @return the frame type that caused the error
     */
    public long getFrameType()
    {
        return frameType;
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s,%s,frame=0x%02X]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getErrorCode(), getMessage(), getFrameType());
    }
}
