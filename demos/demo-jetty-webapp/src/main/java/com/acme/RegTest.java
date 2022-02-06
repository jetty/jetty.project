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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.StringUtil;

/**
 * Rego Servlet - tests being accessed from servlet 3.0 programmatic
 * configuration.
 */
public class RegTest extends HttpServlet
{

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
    {
        request.setCharacterEncoding("UTF-8");
        PrintWriter pout = null;

        try
        {
            pout = response.getWriter();
        }
        catch (IllegalStateException e)
        {
            pout = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), "UTF-8"));
        }

        try
        {
            pout.write("<html>\n<body>\n");
            pout.write("<h1>Rego Servlet</h1>\n");
            pout.write("<table width=\"95%\">");
            pout.write("<tr>\n");
            pout.write("<th align=\"right\">getMethod:&nbsp;</th>");
            pout.write("<td>" + notag(request.getMethod()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getContentLength:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getContentLength()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getContentType:&nbsp;</th>");
            pout.write("<td>" + notag(request.getContentType()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestURI:&nbsp;</th>");
            pout.write("<td>" + notag(request.getRequestURI()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestURL:&nbsp;</th>");
            pout.write("<td>" + notag(request.getRequestURL().toString()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getContextPath:&nbsp;</th>");
            pout.write("<td>" + request.getContextPath() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServletPath:&nbsp;</th>");
            pout.write("<td>" + notag(request.getServletPath()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getPathInfo:&nbsp;</th>");
            pout.write("<td>" + notag(request.getPathInfo()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getPathTranslated:&nbsp;</th>");
            pout.write("<td>" + notag(request.getPathTranslated()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getQueryString:&nbsp;</th>");
            pout.write("<td>" + notag(request.getQueryString()) + "</td>");
            pout.write("</tr><tr>\n");

            pout.write("<th align=\"right\">getProtocol:&nbsp;</th>");
            pout.write("<td>" + request.getProtocol() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getScheme:&nbsp;</th>");
            pout.write("<td>" + request.getScheme() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServerName:&nbsp;</th>");
            pout.write("<td>" + notag(request.getServerName()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServerPort:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getServerPort()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalName:&nbsp;</th>");
            pout.write("<td>" + request.getLocalName() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalAddr:&nbsp;</th>");
            pout.write("<td>" + request.getLocalAddr() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalPort:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getLocalPort()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteUser:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteUser() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getUserPrincipal:&nbsp;</th>");
            pout.write("<td>" + request.getUserPrincipal() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteAddr:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteAddr() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteHost:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteHost() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemotePort:&nbsp;</th>");
            pout.write("<td>" + request.getRemotePort() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestedSessionId:&nbsp;</th>");
            pout.write("<td>" + request.getRequestedSessionId() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isSecure():&nbsp;</th>");
            pout.write("<td>" + request.isSecure() + "</td>");

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isUserInRole(admin):&nbsp;</th>");
            pout.write("<td>" + request.isUserInRole("admin") + "</td>");

            pout.write("</tr></table>");
        }
        catch (Exception e)
        {
            getServletContext().log("dump " + e);
        }

        pout.write("</body>\n</html>\n");

        pout.close();
    }

    @Override
    public String getServletInfo()
    {
        return "Rego Servlet";
    }

    private String notag(String s)
    {
        if (s == null)
            return "null";
        s = StringUtil.replace(s, "&", "&amp;");
        s = StringUtil.replace(s, "<", "&lt;");
        s = StringUtil.replace(s, ">", "&gt;");
        return s;
    }
}
