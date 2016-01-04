//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.cdi.servlet;

import java.io.IOException;
import java.util.Calendar;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.cdi.core.NamedLiteral;

@SuppressWarnings("serial")
@WebServlet(urlPatterns = "/time")
public class TimeServlet extends HttpServlet
{
    @Inject
    @Any
    Instance<TimeFormatter> formatters;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");

        String timeType = req.getParameter("type");
        TimeFormatter time = LocaleTimeFormatter.INSTANCE;

        Instance<TimeFormatter> inst = formatters.select(new NamedLiteral(timeType));
        if (!inst.isAmbiguous() && !inst.isUnsatisfied())
        {
            time = inst.get();
        }

        resp.getWriter().println(time.format(Calendar.getInstance()));
    }
}
