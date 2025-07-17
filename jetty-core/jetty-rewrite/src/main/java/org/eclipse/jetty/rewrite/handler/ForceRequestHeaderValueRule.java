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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.StringUtil;

public class ForceRequestHeaderValueRule extends Rule
{
    private String headerName;
    private String headerValue;
    private HttpField field;

    public String getHeaderName()
    {
        return headerName;
    }

    public void setHeaderName(String headerName)
    {
        this.headerName = headerName;
        this.field = new HttpField(headerName, headerValue);
    }

    public String getHeaderValue()
    {
        return headerValue;
    }

    public void setHeaderValue(String headerValue)
    {
        this.headerValue = headerValue;
        if (!StringUtil.isBlank(headerName))
            this.field = new HttpField(headerName, headerValue);
        else
            this.field = null;
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        HttpFields headers = input.getHeaders();
        String existingValue = (field == null || field.getHeader() == null) ? headers.get(headerName) : headers.get(field.getHeader());

        // No hit, skip this rule.
        if (existingValue == null)
            return null;

        // Already what we expect, skip this rule.
        if (existingValue.equals(headerValue))
            return null;

        HttpFields.Mutable newHeaders = HttpFields.build(headers);
        if (field != null)
            newHeaders.put(field);
        else
            newHeaders.put(headerName, headerValue);
        return new Handler(input)
        {
            @Override
            public HttpFields getHeaders()
            {
                return newHeaders;
            }
        };
    }
}
