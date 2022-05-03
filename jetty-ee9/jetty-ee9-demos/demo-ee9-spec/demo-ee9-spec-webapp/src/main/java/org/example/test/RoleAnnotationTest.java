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

package org.example.test;

import java.io.IOException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RoleAnnotationTest
 *
 * Use DeclareRolesAnnotations from within Jetty.
 */
@DeclareRoles({"server-administrator", "user"})
public class RoleAnnotationTest extends HttpServlet
{
    private ServletConfig _config;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        _config = config;
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<head><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></head>");
            out.println("<h1>Jetty DeclareRoles Annotation Results</h1>");
            out.println("<body>");

            out.println("<h2>Roles</h2>");
            boolean result = request.isUserInRole("other");
            out.println("<br/><b>Result: isUserInRole(\"other\")=" + result + ":" + (result == false ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");

            result = request.isUserInRole("manager");
            out.println("<br/><b>Result: isUserInRole(\"manager\")=" + result + ":" + (result ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");
            result = request.isUserInRole("user");
            out.println("<br/><b>Result: isUserInRole(\"user\")=" + result + ":" + (result ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");
            String context = _config.getServletContext().getContextPath();
            if (!context.endsWith("/"))
                context += "/";

            out.println("<p><a href=\"" + context + "logout.jsp\">Logout</A></p>");

            out.println("</body>");
            out.println("</html>");
            out.flush();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }
}
