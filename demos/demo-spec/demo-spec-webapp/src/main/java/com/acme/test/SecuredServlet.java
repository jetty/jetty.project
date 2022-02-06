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
        writer.println("<head><link rel=\"stylesheet\" type=\"text/css\"  href=\"../stylesheet.css\"/></head>");
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
        writer.println("<p><a href=\"" + context + "logout.jsp\">Logout</A></p>");
        writer.println("</body>");
        writer.println("</html>");
        writer.flush();
        writer.close();
    }
}
