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

import java.util.Locale;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

public class HttpFieldsContainsHeaderValue extends TypeSafeMatcher<HttpFields>
{
    private final String keyName;
    private final String value;

    public HttpFieldsContainsHeaderValue(String keyName, String value)
    {
        this.keyName = keyName;
        this.value = value;
    }

    public HttpFieldsContainsHeaderValue(HttpHeader header, String value)
    {
        this(header.asString(), value);
    }

    @Override
    public void describeTo(Description description)
    {
        description.appendText("expecting http header ").appendValue(keyName).appendText(" with value ").appendValue(value);
    }

    @Override
    protected boolean matchesSafely(HttpFields fields)
    {
        HttpField field = fields.getField(this.keyName);
        if (field == null)
            return false;

        // Use HttpField.contains() logic
        if (field.contains(this.value))
            return true;

        // Simple equals
        if (this.value == field.getValue())
            return true;

        // Try individual value logic
        String lcValue = this.value.toLowerCase(Locale.ENGLISH);
        for (String value : field.getValues())
        {
            if (value.toLowerCase(Locale.ENGLISH).contains(lcValue))
                return true;
        }
        return false;
    }
}
