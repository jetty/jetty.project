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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
        baseRequest.getHttpFields().remove(headerName);
        baseRequest.getHttpFields().add(headerName, forcedValue);
        return target;
    }
}
