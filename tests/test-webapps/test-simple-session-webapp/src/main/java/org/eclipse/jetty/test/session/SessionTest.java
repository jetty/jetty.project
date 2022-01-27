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

package org.eclipse.jetty.test.session;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class SessionTest extends HttpServlet
{

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException
    {
        String action = req.getParameter("action");
        if ("CREATE".equals(action))
        {
            HttpSession session = req.getSession(true);
            session.setAttribute("CHOCOLATE", new Chocolate());
            resp.getOutputStream().println("SESSION CREATED");
        }
        else
        {
            HttpSession session = req.getSession(false);
            Chocolate yummi = (Chocolate)session.getAttribute("CHOCOLATE");
            resp.getOutputStream().println("SESSION READ CHOCOLATE THE BEST:" + yummi.getTheBest());
        }
    }
}
