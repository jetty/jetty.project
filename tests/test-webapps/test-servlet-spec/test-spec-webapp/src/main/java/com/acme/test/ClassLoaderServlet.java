//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.PrintWriter;
import java.net.URI;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/classloader")
public class ClassLoaderServlet extends HttpServlet
{
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException
    {
        try
        {
            PrintWriter writer = resp.getWriter();
            writer.println("<html>");
            writer.println("<HEAD><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></HEAD>");
            writer.println("<body>");
            writer.println("<h1>ClassLoader Isolation Test</h1>");

            writer.printf("<p>Java version: %s</p>%n", System.getProperty("java.version"));

            Class<?> webappIO = Thread.currentThread().getContextClassLoader().loadClass("org.eclipse.jetty.util.IO");
            URI webappURI = ClassInfo.getLocationOfClass(webappIO);

            Class<?> serverIO = req.getServletContext().getClass().getClassLoader().loadClass("org.eclipse.jetty.util.IO");
            URI serverURI = ClassInfo.getLocationOfClass(serverIO);

            writer.printf("<p>Webapp loaded <code>org.eclipse.jetty.util.IO</code> from %s%n", webappURI);
            writer.printf("<br/>Server loaded <code>org.eclipse.jetty.util.IO</code> from %s%n", serverURI);

            if (webappURI.equals(serverURI))
                writer.println("<br/><b>URI Result: <span class=\"fail\">FAIL</span></b></p>");
            else
                writer.println("<br/><b>URI Result: <span class=\"pass\">PASS</span></b></p>");

            writer.println("</body>");
            writer.println("</html>");
            writer.flush();
            writer.close();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }
}
