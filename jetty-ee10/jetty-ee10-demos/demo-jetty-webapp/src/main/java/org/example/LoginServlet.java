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

package org.example;

import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Dump Servlet Request.
 */
public class LoginServlet extends HttpServlet
{

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        response.setContentType("text/html");
        ServletOutputStream out = response.getOutputStream();
        out.println("<html>");
        out.println("<br/>Before getUserPrincipal=" + request.getUserPrincipal());
        out.println("<br/>Before getRemoteUser=" + request.getRemoteUser());
        String param = request.getParameter("action");

        if ("login".equals(param))
        {
            request.login("jetty", "jetty");
        }
        else if ("logout".equals(param))
        {
            request.logout();
        }
        else if ("wrong".equals(param))
        {
            request.login("jetty", "123");
        }

        out.println("<br/>After getUserPrincipal=" + request.getUserPrincipal());
        out.println("<br/>After getRemoteUser=" + request.getRemoteUser());
        out.println("</html>");
        out.flush();
    }
}
