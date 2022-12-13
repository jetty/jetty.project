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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;

public class ForceRequestHeaderValueRule extends Rule
{
    private String headerName;
    private String forcedValue;

    public String getHeaderName()
    {
        return headerName;
    }

    public void setHeaderName(String headerName)
    {
        this.headerName = headerName;
    }

    public String getForcedValue()
    {
        return forcedValue;
    }

    public void setForcedValue(String forcedValue)
    {
        this.forcedValue = forcedValue;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException
    {
        String existingValue = httpServletRequest.getHeader(headerName);
        if (existingValue == null)
        {
            // no hit, skip this rule.
            return null;
        }

        if (existingValue.equals(forcedValue))
        {
            // already what we expect, skip this rule.
            return null;
        }

        Request baseRequest = Request.getBaseRequest(httpServletRequest);
        if (baseRequest == null)
            return null;

        HttpFields.Mutable replacement = HttpFields.build(baseRequest.getHttpFields())
            .remove(headerName)
            .add(headerName, forcedValue);
        baseRequest.setHttpFields(replacement);
        return target;
    }
}
