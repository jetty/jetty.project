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

package org.eclipse.jetty.http.tools.matchers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.hamcrest.Matcher;

public class HttpFieldsMatchers
{
    public static Matcher<HttpFields> containsHeader(String keyName)
    {
        return new HttpFieldsContainsHeaderKey(keyName);
    }

    public static Matcher<HttpFields> containsHeader(HttpHeader header)
    {
        return new HttpFieldsContainsHeaderKey(header);
    }

    public static Matcher<HttpFields> headerValue(String keyName, String value)
    {
        return new HttpFieldsHeaderValue(keyName, value);
    }

    public static Matcher<HttpFields> containsHeaderValue(String keyName, String value)
    {
        return new HttpFieldsContainsHeaderValue(keyName, value);
    }

    public static Matcher<HttpFields> containsHeaderValue(HttpHeader header, String value)
    {
        return new HttpFieldsContainsHeaderValue(header, value);
    }
}
