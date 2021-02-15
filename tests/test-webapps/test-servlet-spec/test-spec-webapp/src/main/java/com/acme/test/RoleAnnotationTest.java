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

package com.acme.test;

import java.io.IOException;
import javax.annotation.security.DeclareRoles;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
            out.println("<HEAD><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></HEAD>");
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

            out.println("<p><A HREF=\"" + context + "logout.jsp\">Logout</A></p>");

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
