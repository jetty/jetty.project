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

package org.eclipse.jetty.tests.webapp;

import java.io.IOException;
import java.util.Enumeration;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet declaring the various do* methods.
 *
 * The Jetty internals for OPTIONS should detect the declared do* methods and
 * return an appropriate listing of available OPTIONS on an OPTIONS request.
 */
public class HttpMethodsServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    /**
     * @see HttpServlet#HttpServlet()
     */
    public HttpMethodsServlet()
    {
        super();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        /* do nothing */
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        /* do nothing */
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        /* do nothing */
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        /* do nothing */
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        /* do nothing */
    }

    @Override
    protected void doTrace(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        response.addHeader("Content-Type", "message/http");
        StringBuffer msg = new StringBuffer();
        msg.append(request.getMethod()).append(' ');
        msg.append(request.getRequestURI()).append(' ');
        msg.append(request.getProtocol()).append("\n");

        // Now the headers
        Enumeration enNames = request.getHeaderNames();
        while (enNames.hasMoreElements())
        {
            String name = (String)enNames.nextElement();
            Enumeration enValues = request.getHeaders(name);
            while (enValues.hasMoreElements())
            {
                String value = (String)enValues.nextElement();
                msg.append(name).append(": ").append(value).append("\n");
            }
        }
        msg.append("\n");

        response.getWriter().print(msg.toString());
    }
}
