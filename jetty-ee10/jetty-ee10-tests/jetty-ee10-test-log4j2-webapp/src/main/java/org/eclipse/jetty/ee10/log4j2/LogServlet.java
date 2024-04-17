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

package org.eclipse.jetty.ee10.log4j2;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(LogServlet.class);

    @Override
    public void init()
    {
        LOG.info("#### init()");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        LOG.info("doGet() req={}", req);

        resp.setCharacterEncoding("utf-8");
        resp.setContentType("text/plain");

        resp.getWriter().println("GET at " + this.getClass().getSimpleName());
    }
}
