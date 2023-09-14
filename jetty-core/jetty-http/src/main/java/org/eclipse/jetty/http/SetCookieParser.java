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

/**
 * <p>A parser for {@code Set-Cookie} header values.</p>
 * <p>Differently from other HTTP headers, {@code Set-Cookie} cannot be multi-valued
 * with values separated by commas, because cookies supports the {@code Expires}
 * attribute whose value is an RFC 1123 date that contains a comma.</p>
 */
public interface SetCookieParser
{
    /**
     * <p>Returns an {@link HttpCookie} obtained by parsing the given
     * {@code Set-Cookie} value.</p>
     * <p>Returns {@code null} if the {@code Set-Cookie} value cannot
     * be parsed due to syntax errors.</p>
     *
     * @param setCookieValue the {@code Set-Cookie} value to parse
     * @return the parse {@link HttpCookie} or {@code null}
     */
    HttpCookie parse(String setCookieValue);

    /**
     * @return a new instance of the default {@link SetCookieParser}
     */
    static SetCookieParser newInstance()
    {
        return new RFC6265SetCookieParser();
    }
}
