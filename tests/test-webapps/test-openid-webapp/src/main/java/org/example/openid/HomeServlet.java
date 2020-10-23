//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.example.openid;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/")
public class HomeServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.setContentType("text/html");
        Principal userPrincipal = request.getUserPrincipal();
        if (userPrincipal != null)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> userInfo = (Map<String, Object>)request.getSession().getAttribute("org.eclipse.jetty.security.openid.claims");
            response.getWriter().println("userId: " + userInfo.get("sub") + "<br>");
            response.getWriter().println("name: " + userInfo.get("name") + "<br>");
            response.getWriter().println("email: " + userInfo.get("email") + "<br>");
            response.getWriter().println("<br><a href=\"/logout\">Logout</a>");
        }
        else
        {
            response.getWriter().println("not authenticated");
            response.getWriter().println("<br><a href=\"/login\">Login</a>");
        }
    }
}
