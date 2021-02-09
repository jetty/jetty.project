//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
        this(400, null);
    }

    public BadMessageException(int code)
    {
        this(code, null);
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
