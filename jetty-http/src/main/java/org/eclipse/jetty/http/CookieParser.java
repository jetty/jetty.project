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

import static org.eclipse.jetty.http.CookieCompliance.Violation.Listener;

/**
 * Cookie parser
 */
public interface CookieParser
{
    static CookieParser newParser(CookieCompliance compliance, Listener complianceListener)
    {
        if (compliance != CookieCompliance.RFC6265)
        {
            for (CookieCompliance.Violation violation : compliance.getAllowed())
                if (!CookieCompliance.RFC6265.allows(violation))
                    return new CookieCutter(compliance, complianceListener);
        }
        return new RFC6265CookieParser(compliance, complianceListener);
    }

    void parseField(Handler handler, String field);

    default  void parseFields(Handler handler, List<String> rawFields)
    {
        // For each cookie field
        for (String field : rawFields)
            parseField(handler, field);
    }

    interface Handler
    {
        void addCookie(String name, String value, int version, String domain, String path, String comment);
    }
}
