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
import java.io.PrintWriter;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/sec/*")
@ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
public class SecuredServlet extends HttpServlet
{
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws IOException
    {
        PrintWriter writer = resp.getWriter();
        writer.println("<html>");
        writer.println("<HEAD><link rel=\"stylesheet\" type=\"text/css\"  href=\"../stylesheet.css\"/></HEAD>");
        writer.println("<body>");
        writer.println("<h1>@ServletSecurity</h1>");
        writer.println("<pre>");
        writer.println("@ServletSecurity");
        writer.println("public class SecuredServlet");
        writer.println("</pre>");
        writer.println("<p><b>Result: <span class=\"pass\">PASS</span></b></p>");
        String context = getServletConfig().getServletContext().getContextPath();
        if (!context.endsWith("/"))
            context += "/";
        writer.println("<p><A HREF=\"" + context + "logout.jsp\">Logout</A></p>");
        writer.println("</body>");
        writer.println("</html>");
        writer.flush();
        writer.close();
    }
}
