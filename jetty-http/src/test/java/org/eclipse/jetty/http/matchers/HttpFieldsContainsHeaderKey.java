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

package org.eclipse.jetty.http.matchers;

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
        return fields.containsKey(this.keyName);
    }

    public static Matcher<HttpFields> containsKey(String keyName)
    {
        return new HttpFieldsContainsHeaderKey(keyName);
    }
}
