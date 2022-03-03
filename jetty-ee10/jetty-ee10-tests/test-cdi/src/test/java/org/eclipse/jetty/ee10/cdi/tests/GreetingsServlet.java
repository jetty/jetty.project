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

package org.eclipse.jetty.ee10.cdi.tests;

import java.io.IOException;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class GreetingsServlet extends HttpServlet
{
    @Inject
    @Named("friendly")
    public Greetings greetings;

    @Inject
    BeanManager manager;

    @Override
    public void init()
    {
        if (manager == null)
            throw new IllegalStateException();
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");
        resp.getWriter().print(greetings == null ? "NULL" : greetings.getGreeting());
        resp.getWriter().print(" filtered by ");
        resp.getWriter().println(req.getAttribute("filter"));
        resp.getWriter().println("Beans from " + manager);
        resp.getWriter().println("Listener saw " + req.getServletContext().getAttribute("listener"));
    }
}
