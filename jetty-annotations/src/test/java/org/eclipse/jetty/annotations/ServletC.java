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

package org.eclipse.jetty.annotations;

import java.io.IOException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RunAs;
import javax.servlet.ServletException;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.HttpMethodConstraint;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@DeclareRoles({"alice"})
@WebServlet(urlPatterns = {"/foo/*", "/bah/*"}, name = "CServlet", initParams = {
    @WebInitParam(name = "x", value = "y")
    }, loadOnStartup = 2, asyncSupported = false)
@MultipartConfig(fileSizeThreshold = 1000, maxFileSize = 2000, maxRequestSize = 3000)
@RunAs("admin")
@ServletSecurity(value = @HttpConstraint(rolesAllowed = {"fred", "bill", "dorothy"}), httpMethodConstraints = {
    @HttpMethodConstraint(value = "GET", rolesAllowed =
    {"bob", "carol", "ted"})
})
public class ServletC extends HttpServlet
{
    @Resource(mappedName = "foo", type = Double.class)
    private Double foo;

    @PreDestroy
    public void pre()
    {

    }

    @PostConstruct
    public void post()
    {

    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        response.setContentType("text/html");
        response.getWriter().println("<h1>Annotated Servlet</h1>");
        response.getWriter().println("An annotated Servlet.");
    }
}
