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

package com.acme;

import java.io.IOException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
