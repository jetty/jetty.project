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

import org.eclipse.jetty.http.matchers.HttpFieldsContainsHeaderKey;
import org.eclipse.jetty.http.matchers.HttpFieldsContainsHeaderValue;
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

    public static Matcher<HttpFields> containsHeaderValue(String keyName, String value)
    {
        return new HttpFieldsContainsHeaderValue(keyName, value);
    }

    public static Matcher<HttpFields> containsHeaderValue(HttpHeader header, String value)
    {
        return new HttpFieldsContainsHeaderValue(header, value);
    }
}
