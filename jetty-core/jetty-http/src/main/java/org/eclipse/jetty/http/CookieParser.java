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

import java.util.List;

import static org.eclipse.jetty.http.CookieCompliance.Violation.BAD_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.Listener;

/**
 * <p>Cookie parser.</p>
 * <p>An interface for variations of a cookie parser.</p>
 */
public interface CookieParser
{
    /**
     * <p>A factory method to create a new parser suitable for the compliance mode.</p>
     * @param compliance The compliance mode to use for parsing.
     * @param complianceListener A listener for compliance violations or null.
     * @return A CookieParser instance.
     */
    static CookieParser newParser(Handler handler, CookieCompliance compliance, Listener complianceListener)
    {
        // The RFC6265CookieParser is primarily a RFC6265 parser, but it can handle most
        // defined "violations" so that it effectively becomes a RFC2965 parser. However, it
        // cannot forgive bad quotes.  Thus, we use the legacy CookieCutter parser only if
        // the compliance mode requires BAD QUOTES.
        if (compliance.allows(BAD_QUOTES))
            return new CookieCutter(handler, compliance, complianceListener);
        return new RFC6265CookieParser(handler, compliance, complianceListener);
    }

    void parseField(String field) throws InvalidCookieException;

    default void parseFields(List<String> rawFields) throws InvalidCookieException
    {
        // For each cookie field
        for (String field : rawFields)
            parseField(field);
    }

    /**
     * The handler of parsed cookies.
     */
    interface Handler
    {
        void addCookie(String name, String value, int version, String domain, String path, String comment);
    }

    /**
     * <p>The exception thrown when a cookie cannot be parsed and {@link CookieCompliance.Violation#INVALID_COOKIES} is not allowed.</p>
     */
    class InvalidCookieException extends IllegalArgumentException
    {
        public InvalidCookieException()
        {
            super();
        }

        public InvalidCookieException(String s)
        {
            super(s);
        }

        public InvalidCookieException(String message, Throwable cause)
        {
            super(message, cause);
        }

        public InvalidCookieException(Throwable cause)
        {
            super(cause);
        }
    }
}
