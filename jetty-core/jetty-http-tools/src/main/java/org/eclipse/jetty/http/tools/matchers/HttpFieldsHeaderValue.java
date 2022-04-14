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

import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

public class HttpFieldsHeaderValue extends TypeSafeMatcher<HttpFields>
{
    private final String keyName;
    private final String value;

    public HttpFieldsHeaderValue(String keyName, String value)
    {
        this.keyName = keyName;
        this.value = value;
    }

    public HttpFieldsHeaderValue(HttpHeader header, String value)
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

        return Objects.equals(this.value, field.getValue());
    }
}
