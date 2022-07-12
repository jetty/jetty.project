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

package org.eclipse.jetty.test.openid;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class HomePage extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.setContentType("text/html");
        Principal userPrincipal = request.getUserPrincipal();
        if (userPrincipal != null)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> userInfo = (Map<String, Object>)request.getSession().getAttribute("org.eclipse.jetty.security.openid.claims");
            response.getWriter().println("userId: " + userInfo.get("sub") + "<br>");
            response.getWriter().println("name: " + userInfo.get("name") + "<br>");
            response.getWriter().println("email: " + userInfo.get("email") + "<br>");
            response.getWriter().println("<br><a href=\"" + request.getContextPath() + "/logout\">Logout</a>");
        }
        else
        {
            response.getWriter().println("not authenticated");
            response.getWriter().println("<br><a href=\"" + request.getContextPath() + "/login\">Login</a>");
        }
    }
}
