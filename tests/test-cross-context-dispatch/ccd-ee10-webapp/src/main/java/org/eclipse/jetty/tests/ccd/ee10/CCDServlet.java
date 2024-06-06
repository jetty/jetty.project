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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.tests.ccd.common.DispatchPlan;
import org.eclipse.jetty.tests.ccd.common.Step;
import org.eclipse.jetty.tests.ccd.common.steps.ContextRedispatchStep;
import org.eclipse.jetty.tests.ccd.common.steps.GetHttpSessionStep;
import org.eclipse.jetty.tests.ccd.common.steps.RequestDispatchStep;

public class CCDServlet extends HttpServlet
{
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        DispatchPlan dispatchPlan = (DispatchPlan)req.getAttribute(DispatchPlan.class.getName());

        if (dispatchPlan == null)
            throw new ServletException("Unable to find DispatchPlan");

        dispatchPlan.addEvent("%s.service() dispatcherType=%s method=%s requestUri=%s",
            this.getClass().getName(),
            req.getDispatcherType(), req.getMethod(), req.getRequestURI());

        Step step;

        while ((step = dispatchPlan.popStep()) != null)
        {
            if (step instanceof ContextRedispatchStep contextRedispatchStep)
            {
                ServletContext otherContext = getServletContext().getContext(contextRedispatchStep.getContextPath());
                if (otherContext == null)
                    throw new NullPointerException("ServletContext.getContext(\"" + contextRedispatchStep.getContextPath() + "\") returned null");
                RequestDispatcher dispatcher = otherContext.getRequestDispatcher(contextRedispatchStep.getDispatchPath());
                if (dispatcher == null)
                    throw new NullPointerException("ServletContext.getRequestDispatcher(\"" + contextRedispatchStep.getDispatchPath() + "\") returned null");
                switch (contextRedispatchStep.getDispatchType())
                {
                    case FORWARD -> dispatcher.forward(req, resp);
                    case INCLUDE -> dispatcher.include(req, resp);
                }
                return;
            }
            else if (step instanceof RequestDispatchStep requestDispatchStep)
            {
                RequestDispatcher dispatcher = req.getRequestDispatcher(requestDispatchStep.getDispatchPath());
                if (dispatcher == null)
                    throw new NullPointerException("HttpServletRequest.getRequestDispatcher(\"" + requestDispatchStep.getDispatchPath() + "\") returned null");
                switch (requestDispatchStep.getDispatchType())
                {
                    case FORWARD -> dispatcher.forward(req, resp);
                    case INCLUDE -> dispatcher.include(req, resp);
                }
                return;
            }
            else if (step instanceof GetHttpSessionStep)
            {
                req.getSession(true);
            }
            else
            {
                throw new RuntimeException("Unable to execute task " + step + " in " + this.getClass().getName());
            }
        }
    }
}
