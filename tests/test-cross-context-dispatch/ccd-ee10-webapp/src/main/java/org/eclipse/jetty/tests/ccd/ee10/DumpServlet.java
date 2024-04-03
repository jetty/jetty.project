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

package org.eclipse.jetty.tests.ccd.ee10;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.tests.ccd.common.DispatchPlan;

public class DumpServlet extends HttpServlet
{
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("text/plain");

        DispatchPlan dispatchPlan = (DispatchPlan)req.getAttribute(DispatchPlan.class.getName());

        if (dispatchPlan == null)
            throw new ServletException("Unable to find DispatchPlan");

        dispatchPlan.addEvent("DumpServlet.service() dispatcherType=%s method=%s requestUri=%s",
            req.getDispatcherType(), req.getMethod(), req.getRequestURI());

        PrintWriter out = resp.getWriter();
        for (String event: dispatchPlan.getEvents())
            out.println(event);
    }
}
