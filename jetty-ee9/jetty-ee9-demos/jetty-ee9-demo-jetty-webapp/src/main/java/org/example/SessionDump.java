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

package org.example;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Enumeration;
import java.util.UUID;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.util.MultiMap;

/**
 * Test Servlet Sessions.
 */
@SuppressWarnings("serial")
public class SessionDump extends HttpServlet
{
    /**
     * Simple object attribute to test serialization
     */
    public static class ObjectAttributeValue implements java.io.Serializable
    {
        long l;

        public ObjectAttributeValue(long l)
        {
            this.l = l;
        }

        public long getValue()
        {
            return l;
        }
    }

    int redirectCount = 0;

    @Override
    public void init(ServletConfig config)
        throws ServletException
    {
        super.init(config);
    }

    protected void handleForm(HttpServletRequest request)
    {
        HttpSession session = request.getSession(false);
        String action = request.getParameter("Action");
        String name = request.getParameter("Name");
        String value = request.getParameter("Value");

        if (action != null)
        {
            if (action.equals("New Session"))
            {
                session = request.getSession(true);
                session.setAttribute("test", "value");
                session.setAttribute("obj", new ObjectAttributeValue(System.currentTimeMillis()));
                session.setAttribute("WEBCL", new MultiMap<>());
                UUID uuid = UUID.randomUUID();
                session.setAttribute("uuid", uuid);
            }
            else if (session != null)
            {
                if (action.equals("Invalidate"))
                    session.invalidate();
                else if (action.equals("Set") && name != null && !name.isEmpty())
                    session.setAttribute(name, value);
                else if (action.equals("Remove"))
                    session.removeAttribute(name);
            }
        }
    }

    @Override
    public void doPost(HttpServletRequest request,
                       HttpServletResponse response)
        throws IOException
    {
        handleForm(request);
        String nextUrl = getURI(request) + "?R=" + redirectCount++;
        String encodedUrl = response.encodeRedirectURL(nextUrl);
        response.sendRedirect(encodedUrl);
    }

    @Override
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response)
        throws IOException
    {
        handleForm(request);

        response.setContentType("text/html");

        HttpSession session = request.getSession(getURI(request).indexOf("new") > 0);
        try
        {
            if (session != null)
                session.isNew();
        }
        catch (IllegalStateException e)
        {
            log("Session already invalidated", e);
            session = null;
        }

        PrintWriter out = response.getWriter();
        out.println("<h1>Session Dump Servlet:</h1>");
        out.println("<form action=\"" + response.encodeURL(getURI(request)) + "\" method=\"post\">");

        if (session == null)
        {
            out.println("<h3>No Session</h3>");
            out.println("<input type=\"submit\" name=\"Action\" value=\"New Session\"/>");
        }
        else
        {
            if (session.getAttribute("WEBCL") == null)
                session.setAttribute("WEBCL", new MultiMap<>());
            try
            {
                out.println("<b>ID:</b> " + session.getId() + "<br/>");
                out.println("<b>New:</b> " + session.isNew() + "<br/>");
                out.println("<b>Created:</b> " + new Date(session.getCreationTime()) + "<br/>");
                out.println("<b>Last:</b> " + new Date(session.getLastAccessedTime()) + "<br/>");
                out.println("<b>Max Inactive:</b> " + session.getMaxInactiveInterval() + "<br/>");
                out.println("<b>Context:</b> " + session.getServletContext() + "<br/>");

                Enumeration<String> keys = session.getAttributeNames();
                while (keys.hasMoreElements())
                {
                    String name = (String)keys.nextElement();
                    String value = "" + session.getAttribute(name);

                    out.println("<b>" + name + ":</b> " + value + "<br/>");
                }

                out.println("<b>Name:</b><input type=\"text\" name=\"Name\" /><br/>");
                out.println("<b>Value:</b><input type=\"text\" name=\"Value\" /><br/>");

                out.println("<input type=\"submit\" name=\"Action\" value=\"Set\"/>");
                out.println("<input type=\"submit\" name=\"Action\" value=\"Remove\"/>");
                out.println("<input type=\"submit\" name=\"Action\" value=\"Refresh\"/>");
                out.println("<input type=\"submit\" name=\"Action\" value=\"Invalidate\"/><br/>");

                out.println("</form><br/>");

                if (request.isRequestedSessionIdFromCookie())
                    out.println("<p>Turn off cookies in your browser to try url encoding<BR>");

                if (request.isRequestedSessionIdFromURL())
                    out.println("<p>Turn on cookies in your browser to try cookie encoding<BR>");
                out.println("<a href=\"" + response.encodeURL(request.getRequestURI() + "?q=0") + "\">Encoded Link</a><BR>");
            }
            catch (IllegalStateException e)
            {
                e.printStackTrace();
            }
        }
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
}
