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

package org.eclipse.jetty.ee10.annotations;

import java.io.IOException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.HttpMethodConstraint;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
