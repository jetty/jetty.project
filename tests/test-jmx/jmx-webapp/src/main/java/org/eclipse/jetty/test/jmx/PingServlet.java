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

package org.eclipse.jetty.test.jmx;

import java.io.IOException;
import java.util.Date;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple ping into this webapp to see if it is here.
 */
@SuppressWarnings("serial")
@ManagedObject("Ping Servlet")
public class PingServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(PingServlet.class);

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        LOG.info("Adding {} to attribute {}", this, config.getServletName());
        config.getServletContext().setAttribute(config.getServletName(), this);
        super.init(config);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");
        resp.getWriter().println(ping());
    }

    @ManagedOperation
    public String ping()
    {
        return "Servlet Pong at " + new Date().toString();
    }
}
