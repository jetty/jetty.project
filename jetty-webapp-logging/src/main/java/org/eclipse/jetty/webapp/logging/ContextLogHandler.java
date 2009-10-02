// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.logging;

import java.io.IOException;
import java.security.Principal;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.MDC;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

/**
 * Adds Logging specific MDC information about the incoming request information.
 */
public class ContextLogHandler extends HandlerWrapper
{
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Collect Info for NDC/MDC
        MDC.put("target",target);
        String contextPath = request.getContextPath();
        if (contextPath != null)
        {
            MDC.put("contextPath",contextPath);
        }
        MDC.put("remoteAddr",request.getRemoteAddr());
        String remoteUser = request.getRemoteUser();
        if (remoteUser != null)
        {
            MDC.put("remoteUser",remoteUser);
        }
        Principal principal = request.getUserPrincipal();
        if (principal != null)
        {
            MDC.put("principal",principal.getName());
        }

        try
        {
            super.handle(target,baseRequest,request,response);
        }
        finally
        {
            // Pop info out / clear the NDC/MDC
            MDC.clear();
        }
    }
}
