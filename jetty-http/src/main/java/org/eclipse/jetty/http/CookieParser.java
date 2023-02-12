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

import java.util.List;

import static org.eclipse.jetty.http.CookieCompliance.Violation.BAD_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.Listener;

/**
 * <p>Cookie parser.</p>
 * <p>An interface for variations of a cookie parser.</p>
 *
 */
public interface CookieParser
{
    /**
     * <p>A factory method to create a new parser suitable for the compliance mode.</p>
     * @param compliance The compliance mode to use for parsing.
     * @param complianceListener A listener for compliance violations or null.
     * @return A CookieParser instance.
     */
    static CookieParser newParser(CookieCompliance compliance, Listener complianceListener)
    {
        if (compliance == CookieCompliance.RFC6265_LEGACY || compliance.allows(BAD_QUOTES))
            return new CookieCutter(compliance, complianceListener);
        return new RFC6265CookieParser(compliance, complianceListener);
    }

    void parseField(Handler handler, String field);

    default  void parseFields(Handler handler, List<String> rawFields)
    {
        // For each cookie field
        for (String field : rawFields)
            parseField(handler, field);
    }

    /**
     * The handler of parsed cookies.
     */
    interface Handler
    {
        void addCookie(String name, String value, int version, String domain, String path, String comment);
    }
}
