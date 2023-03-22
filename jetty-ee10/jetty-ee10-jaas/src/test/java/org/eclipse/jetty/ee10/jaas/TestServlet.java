
//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
        if (_hasRoles == null && _hasntRoles == null)
        {
            try
            {
                req.authenticate(resp);
            }
            catch (ServletException e)
            {
                //TODO: a ServletException should only be thrown here if the response has
                //not been set, but currently it seems that the ServletException is thrown
                //anyway by ServletContextRequest.ServletApiRequest.authenticate.
            }
        }
        else
        {
            testHasRoles(req, resp);
            testHasntRoles(req, resp);

            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.getWriter().println("All OK");
            resp.getWriter().println("requestURI=" + req.getRequestURI());
        }
    }

    private void testHasRoles(HttpServletRequest req, HttpServletResponse resp) throws ServletException
    {
        if (_hasRoles != null)
        {
            for (String role : _hasRoles)
            {
                //TODO: the response may already have been committed, because eg the
                //BasicAuthenticator may have decided the authentication is invalid.
                //Thus throwing ServletException here causes a problem because the HttpChannelState
                //tries to set the response.
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