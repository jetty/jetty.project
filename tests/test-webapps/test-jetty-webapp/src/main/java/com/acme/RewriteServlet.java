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

package com.acme;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Test Servlet Rewrite
 */
@SuppressWarnings("serial")
public class RewriteServlet extends HttpServlet
{

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
    {
        doGet(req, res);
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
    {
        ServletOutputStream out = res.getOutputStream();
        out.println("<html><body><table>");
        out.println("<tr><th>Original request URI: </th><td>" + req.getAttribute("requestedPath") + "</td></tr>");
        out.println("<tr><th>Rewritten request URI: </th><td>" + req.getRequestURI() + "</td></tr>");

        Cookie cookie = null;
        Cookie[] cookies = req.getCookies();
        if (cookies != null)
        {
            for (Cookie c : cookies)
            {
                if (c.getName().equals("visited"))
                {
                    cookie = c;
                    break;
                }
            }
        }
        if (cookie != null)
            out.println("<tr><th>Previously visited: </th></td><td>" + cookie.getValue() + "</td></tr>");

        out.println("</table></body></html>");
    }

    @Override
    public String getServletInfo()
    {
        return "Rewrite Servlet";
    }
}
