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

package org.eclipse.jetty.http;

/**
 * <p>Exception thrown to indicate a Bad HTTP Message has either been received
 * or attempted to be generated.  Typically these are handled with either 400
 * or 500 responses.</p>
 */
@SuppressWarnings("serial")
public class BadMessageException extends RuntimeException
{
    final int _code;
    final String _reason;

    public BadMessageException()
    {
        this(400, null, null);
    }

    public BadMessageException(int code)
    {
        this(code, null, null);
    }

    public BadMessageException(String reason)
    {
        this(400, reason);
    }

    public BadMessageException(String reason, Throwable cause)
    {
        this(400, reason, cause);
    }

    public BadMessageException(int code, String reason)
    {
        this(code, reason, null);
    }

    public BadMessageException(int code, Throwable cause)
    {
        this(code, null, cause);
    }

    public BadMessageException(int code, String reason, Throwable cause)
    {
        super(code + ": " + reason, cause);
        _code = code;
        _reason = reason;
    }

    public int getCode()
    {
        return _code;
    }

    public String getReason()
    {
        return _reason;
    }
}
