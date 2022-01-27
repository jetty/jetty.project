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

package com.acme;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Test Servlet Cookies.
 */
@SuppressWarnings("serial")
public class CookieDump extends HttpServlet
{
    int redirectCount = 0;

    protected void handleForm(HttpServletRequest request,
                              HttpServletResponse response)
    {
        String name = request.getParameter("Name");
        String value = request.getParameter("Value");
        String age = request.getParameter("Age");

        if (name != null && !name.isEmpty())
        {
            Cookie cookie = new Cookie(name, value);
            if (age != null && !age.isEmpty())
                cookie.setMaxAge(Integer.parseInt(age));
            response.addCookie(cookie);
        }
    }

    @Override
    public void doPost(HttpServletRequest request,
                       HttpServletResponse response)
        throws ServletException, IOException
    {
        handleForm(request, response);
        String nextUrl = getURI(request) + "?R=" + redirectCount++;
        String encodedUrl = response.encodeRedirectURL(nextUrl);
        response.sendRedirect(encodedUrl);
    }

    @Override
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response)
        throws ServletException, IOException
    {
        handleForm(request, response);

        response.setContentType("text/html");

        PrintWriter out = response.getWriter();
        out.println("<h1>Cookie Dump Servlet:</h1>");

        Cookie[] cookies = request.getCookies();

        for (int i = 0; cookies != null && i < cookies.length; i++)
        {
            out.println("<b>" + deScript(cookies[i].getName()) + "</b>=" + deScript(cookies[i].getValue()) + "<br/>");
        }

        out.println("<form action=\"" + response.encodeURL(getURI(request)) + "\" method=\"post\">");

        out.println("<b>Name:</b><input type=\"text\" name=\"Name\" value=\"name\"/><br/>");
        out.println("<b>Value:</b><input type=\"text\" name=\"Value\" value=\"value\"/><br/>");
        out.println("<b>Max-Age:</b><input type=\"text\" name=\"Age\" value=\"60\"/><br/>");
        out.println("<input type=\"submit\" name=\"Action\" value=\"Set\"/>");
    }

    @Override
    public String getServletInfo()
    {
        return "Session Dump Servlet";
    }

    private String getURI(HttpServletRequest request)
    {
        String uri = (String)request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (uri == null)
            uri = request.getRequestURI();
        return uri;
    }

    protected String deScript(String string)
    {
        if (string == null)
            return null;
        string = string.replace("&", "&amp;");
        string = string.replace("<", "&lt;");
        string = string.replace(">", "&gt;");
        return string;
    }

    @Override
    public void destroy()
    {
        // For testing --stop with STOP.WAIT handling of the jetty-start behavior.
        if (Boolean.getBoolean("test.slow.destroy"))
        {
            try
            {
                TimeUnit.SECONDS.sleep(10);
            }
            catch (InterruptedException e)
            {
                // ignore
            }
        }
        super.destroy();
    }
}
