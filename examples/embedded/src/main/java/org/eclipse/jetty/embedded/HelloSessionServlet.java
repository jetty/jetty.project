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

package org.eclipse.jetty.embedded;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@SuppressWarnings("serial")
public class HelloSessionServlet extends HttpServlet
{
    public HelloSessionServlet()
    {
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws ServletException,
        IOException
    {
        response.setContentType("text/html");
        response.setStatus(HttpServletResponse.SC_OK);
        response.addHeader("Cache-Control", "no-cache");

        HttpSession session = request.getSession();
        String message;
        String link;

        String greeting = request.getParameter("greeting");
        if (greeting != null)
        {
            session.setAttribute("greeting", greeting);
            message = "New greeting '" + greeting + "' set in session.";
            link = "Click <a href=\"/\">here</a> to use the new greeting from the session.";
        }
        else
        {
            greeting = (String)session.getAttribute("greeting");

            if (greeting != null)
            {
                message = "Greeting '" + greeting + "' set from session.";
            }
            else
            {
                greeting = "Hello";
                message = "Greeting '" + greeting + "' is default.";
            }

            link = "Click <a href=\"/?greeting=Hola\">here</a> to set a new greeting.";
        }

        PrintWriter out = response.getWriter();
        out.println("<h1>" + greeting + " from HelloSessionServlet</h1>");
        out.println("<p>" + message + "</p>");
        out.println("<pre>");
        out.println("session.getId() = " + session.getId());
        out.println("session.isNew() = " + session.isNew());
        out.println("</pre>");
        out.println("<p>" + link + "</p>");
    }
}

