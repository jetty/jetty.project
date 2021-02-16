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

package org.example.openid;

import java.io.IOException;
import java.io.PrintWriter;
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
        PrintWriter writer = response.getWriter();
        Principal userPrincipal = request.getUserPrincipal();
        if (userPrincipal != null)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> openIdInfo = (Map<String, Object>)request.getSession().getAttribute("org.eclipse.jetty.security.openid.claims");
            if (openIdInfo != null)
            {
                writer.println("<h3>OpenID Authenticated User</h3>");
                writer.println("userId: " + openIdInfo.get("sub") + "<br>");
                writer.println("name: " + openIdInfo.get("name") + "<br>");
                writer.println("email: " + openIdInfo.get("email") + "<br>");
            }
            else
            {
                writer.println("<h3>Authenticated User</h3>");
                writer.println("name: " + userPrincipal.getName() + "<br>");
                writer.println("principal: " + userPrincipal.toString() + "<br>");
            }

            writer.println("<br><a href=\"/logout\">Logout</a>");
        }
        else
        {
            writer.println("not authenticated");
            writer.println("<br><a href=\"/login\">Form Login</a>");
            writer.println("<br><a href=\"/openid/login\">OpenID Login</a>");
        }
    }
}
