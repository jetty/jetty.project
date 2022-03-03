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

package org.eclipse.jetty.ee10.demos;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

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

