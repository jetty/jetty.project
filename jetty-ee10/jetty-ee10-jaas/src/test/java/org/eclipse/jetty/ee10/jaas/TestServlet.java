
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

package org.eclipse.jetty.ee10.jaas;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class TestServlet extends HttpServlet
{
    private List<String> _hasRoles;
    private List<String> _hasntRoles;
    
    public TestServlet(List<String> hasRoles, List<String> hasntRoles)
    {
        _hasRoles = hasRoles;
        _hasntRoles = hasntRoles;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        testHasRoles(req, resp);
        testHasntRoles(req, resp);

        resp.setStatus(200);
        resp.setContentType("text/plain");
        resp.getWriter().println("All OK");
        resp.getWriter().println("requestURI=" + req.getRequestURI());
    }

    private void testHasRoles(HttpServletRequest req, HttpServletResponse resp) throws ServletException
    {
        if (_hasRoles != null)
        {
            for (String role : _hasRoles)
            {
                if (!req.isUserInRole(role))
                    throw new ServletException("! in role " + role);
            }
        }
    }
    
    private void testHasntRoles(HttpServletRequest req, HttpServletResponse resp) throws ServletException
    {
        if (_hasntRoles != null)
        {
            for (String role : _hasntRoles)
            {
                if (req.isUserInRole(role))
                    throw new ServletException("in role " + role);
            }
        }
    }
}