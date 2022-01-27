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

package org.eclipse.jetty.cdi.tests;

import java.io.IOException;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
