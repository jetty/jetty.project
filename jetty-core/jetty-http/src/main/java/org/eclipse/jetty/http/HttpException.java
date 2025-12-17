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
 * <p>A tagging interface for Exceptions that carry an HTTP response code and reason.</p>
 * <p>Exception subclasses that implement this interface will be caught by the container
 * and the {@link #getCode()} used to send a response.</p>
 */
public interface HttpException extends QuietException
{
    int getCode();

    String getReason();

    static HttpException asHttpException(Throwable throwable)
    {
        if (throwable instanceof IllegalArgumentException iae)
            return iae;
        if (throwable instanceof IllegalStateException ise)
            return ise;
        if (throwable instanceof RuntimeException re)
            return re;
        if (throwable instanceof java.lang.IllegalArgumentException)
            return new HttpException.IllegalArgumentException(HttpStatus.BAD_REQUEST_400, throwable.getMessage(), throwable);
        if (throwable instanceof java.lang.IllegalStateException)
            return new HttpException.IllegalStateException(HttpStatus.BAD_REQUEST_400, throwable.getMessage(), throwable);
        if (throwable instanceof java.lang.RuntimeException)
            return new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, throwable.getMessage(), throwable);
        return new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, throwable.getMessage(), throwable);
    }

    /**
     * Throw the given HttpException as an unchecked exception.
     *
     * @param httpException the HttpException to throw
     */
    static void throwAsUnchecked(HttpException httpException)
    {
        if (httpException instanceof Throwable throwable)
            ExceptionUtil.ifExceptionThrowUnchecked(throwable);
        throw new IllegalStateException(httpException.getCode(), httpException.getReason());
    }

    /**
     * Convert the given Throwable to an HttpException and throw it as an unchecked exception.
     *
     * @param throwable the Throwable to convert and throw
     */
    static void throwAsUncheckedHttpException(Throwable throwable)
    {
        throwAsUnchecked(asHttpException(throwable));
    }

    /**
     * If the given Throwable is an HttpException, throw it as an unchecked exception.
     *
     * @param th the Throwable to check and possibly throw
     */
    static void throwIfHttpException(Throwable th)
    {
        if (th instanceof HttpException he)
            HttpException.throwAsUnchecked(he);
    }

    /**
     * A RuntimeException version of HttpException.
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
            assert code == 0 || HttpStatus.isClientError(code) || HttpStatus.isServerError(code);
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

        public IllegalStateException asIllegalStateException()
        {
            return new IllegalStateException(_code, _reason, getCause());
        }
    }

    /**
     * An IllegalArgumentException version of HttpException.
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
            assert code == 0 || HttpStatus.isClientError(code) || HttpStatus.isServerError(code);
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
     * An IllegalStateException version of HttpException.
     */
    class IllegalStateException extends java.lang.IllegalStateException implements HttpException
    {
        private final int _code;
        private final String _reason;

        public IllegalStateException(int code)
        {
            this(code, null, null);
        }

        public IllegalStateException(int code, String reason)
        {
            this(code, reason, null);
        }

        public IllegalStateException(int code, String reason, Throwable cause)
        {
            super(code + ": " + reason, cause);
            assert code == 0 || HttpStatus.isClientError(code) || HttpStatus.isServerError(code);
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
