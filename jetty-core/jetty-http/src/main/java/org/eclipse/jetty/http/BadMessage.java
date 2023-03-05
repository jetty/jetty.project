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

/**
 * <p>Exception thrown to indicate a Bad HTTP Message has either been received
 * or attempted to be generated.  Typically these are handled with either 400
 * or 500 responses.</p>
 */
@SuppressWarnings("serial")
public interface BadMessage extends QuietException
{
    int getCode();

    String getReason();

    /**
     * <p>Exception thrown to indicate a Bad HTTP Message has either been received
     * or attempted to be generated.  Typically these are handled with either 400
     * or 500 responses.</p>
     */
    @SuppressWarnings("serial")
    class RuntimeException extends java.lang.RuntimeException implements BadMessage
    {
        final int _code;
        final String _reason;

        public RuntimeException()
        {
            this(400, null, null);
        }

        public RuntimeException(int code)
        {
            this(code, null, null);
        }

        public RuntimeException(String reason)
        {
            this(400, reason);
        }

        public RuntimeException(String reason, Throwable cause)
        {
            this(400, reason, cause);
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
    @SuppressWarnings("serial")
    class IllegalArgumentException extends java.lang.IllegalArgumentException implements BadMessage
    {
        final int _code;
        final String _reason;

        public IllegalArgumentException()
        {
            this(400, null, null);
        }

        public IllegalArgumentException(String reason)
        {
            this(400, reason, null);
        }

        public IllegalArgumentException(String reason, Throwable cause)
        {
            this(400, reason, cause);
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
