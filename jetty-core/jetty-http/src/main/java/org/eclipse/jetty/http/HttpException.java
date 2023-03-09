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

package org.eclipse.jetty.http;

import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.ExceptionUtil;

/**
 * <p>A tagging interface for Exceptions that carry a HTTP response code and reason.</p>
 * <p>Exception sub-classes that implement this interface will be caught by the container
 * and the {@link #getCode()} used to send a response.</p>
 */
public interface HttpException extends QuietException
{
    int getCode();

    String getReason();

    static void throwAsUnchecked(HttpException httpException)
    {
        ExceptionUtil.ifExceptionThrowUnchecked((Throwable)httpException);
    }

    /**
     * <p>Exception thrown to indicate a Bad HTTP Message has either been received
     * or attempted to be generated.  Typically these are handled with either 400
     * or 500 responses.</p>
     */
    class RuntimeException extends java.lang.RuntimeException implements HttpException
    {
        private final int _code;
        private final String _reason;

        public RuntimeException(int code)
        {
            this(code, null, null);
        }

        public RuntimeException(int code, String reason)
        {
            this(code, reason, null);
        }

        public RuntimeException(int code, Throwable cause)
        {
            this(code, null, cause);
        }

        public RuntimeException(int code, String reason, Throwable cause)
        {
            super(code + ": " + reason, cause);
            _code = code;
            _reason = reason;
        }

        @Override
        public int getCode()
        {
            return _code;
        }

        @Override
        public String getReason()
        {
            return _reason;
        }
    }

    /**
     * <p>Exception thrown to indicate a Bad HTTP Message has either been received
     * or attempted to be generated.  Typically these are handled with either 400
     * or 500 responses.</p>
     */
    class IllegalArgumentException extends java.lang.IllegalArgumentException implements HttpException
    {
        private final int _code;
        private final String _reason;

        public IllegalArgumentException(int code)
        {
            this(code, null, null);
        }

        public IllegalArgumentException(int code, String reason)
        {
            this(code, reason, null);
        }

        public IllegalArgumentException(int code, String reason, Throwable cause)
        {
            super(code + ": " + reason, cause);
            _code = code;
            _reason = reason;
        }

        @Override
        public int getCode()
        {
            return _code;
        }

        @Override
        public String getReason()
        {
            return _reason;
        }
    }
}
