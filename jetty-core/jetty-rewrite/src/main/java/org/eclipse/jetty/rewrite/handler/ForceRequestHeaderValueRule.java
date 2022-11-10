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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpFields;

public class ForceRequestHeaderValueRule extends Rule
{
    private String headerName;
    private String headerValue;

    public String getHeaderName()
    {
        return headerName;
    }

    public void setHeaderName(String headerName)
    {
        this.headerName = headerName;
    }

    public String getHeaderValue()
    {
        return headerValue;
    }

    public void setHeaderValue(String headerValue)
    {
        this.headerValue = headerValue;
    }

    @Override
    public RequestProcessor matchAndApply(RequestProcessor input) throws IOException
    {
        HttpFields headers = input.getHeaders();
        String existingValue = headers.get(headerName);

        // No hit, skip this rule.
        if (existingValue == null)
            return null;

        // Already what we expect, skip this rule.
        if (existingValue.equals(headerValue))
            return null;

        HttpFields.Mutable newHeaders = HttpFields.build(headers);
        newHeaders.remove(headerName);
        newHeaders.add(headerName, headerValue);
        return new RequestProcessor(input)
        {
            @Override
            public HttpFields getHeaders()
            {
                return newHeaders;
            }
        };
    }
}
