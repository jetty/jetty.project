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

/**
 * <p>Base class for HTTP/3 exception, carrying an HTTP/3 error code and a reason.</p>
 */
public abstract sealed class HTTP3Exception extends RuntimeException
{
    private final long errorCode;

    protected HTTP3Exception(long errorCode, String reason)
    {
        super(reason);
        this.errorCode = errorCode;
    }

    public long getErrorCode()
    {
        return errorCode;
    }

    public String getReason()
    {
        return getMessage();
    }

    /**
     * <p>HTTP/3 exception that affects a stream, not the session.</p>
     */
    public static final class StreamException extends HTTP3Exception
    {
        public StreamException(HTTP3ErrorCode errorCode, String message)
        {
            this(errorCode.code(), message);
        }

        public StreamException(long errorCode, String message)
        {
            super(errorCode, message);
        }
    }

    /**
     * <p>HTTP/3 exception that affects the session.</p>
     */
    public static final class SessionException extends HTTP3Exception
    {
        public SessionException(HTTP3ErrorCode errorCode, String message)
        {
            this(errorCode.code(), message);
        }

        public SessionException(long errorCode, String reason)
        {
            super(errorCode, reason);
        }
    }
}
