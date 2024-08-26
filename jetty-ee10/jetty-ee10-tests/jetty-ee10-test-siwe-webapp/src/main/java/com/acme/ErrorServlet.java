//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package com.acme;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/error")
public class ErrorServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
    {

        response.setContentType("text/html");
        response.getWriter().println("<h1>error: not authorized</h1>");
        response.getWriter().println("<p>" + request.getUserPrincipal() + "</p>");
        response.getWriter().println("<p>" + request.getParameter("error_description_jetty") + "</p>");
        String home = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        response.getWriter().println("<a href=\"" + home + "\">Home</a><br>");
    }
}
