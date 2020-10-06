//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.tools.matchers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

public class HttpFieldsContainsHeaderKey extends TypeSafeMatcher<HttpFields>
{
    private final String keyName;

    public HttpFieldsContainsHeaderKey(String keyName)
    {
        this.keyName = keyName;
    }

    public HttpFieldsContainsHeaderKey(HttpHeader header)
    {
        this.keyName = header.asString();
    }

    @Override
    public void describeTo(Description description)
    {
        description.appendText("expecting http field name ").appendValue(keyName);
    }

    @Override
    protected boolean matchesSafely(HttpFields fields)
    {
        return fields.contains(this.keyName);
    }

    public static Matcher<HttpFields> containsKey(String keyName)
    {
        return new HttpFieldsContainsHeaderKey(keyName);
    }
}
