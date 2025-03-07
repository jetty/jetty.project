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

package org.example.test;

import java.io.IOException;
import java.util.function.Supplier;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

public class DurableDispatchServlet extends HttpServlet
{
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getDispatcherType() == DispatcherType.INCLUDE)
        {
            Supplier<String> getServletPath = () -> (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            request.setAttribute("include.getServletPath", getServletPath);
            request.setAttribute("include.servletPath", getServletPath.get());

            String pathInfo = (String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            if (pathInfo == null)
                pathInfo = "/pathInfo";
            RequestDispatcher dispatcher = getServletContext().getRequestDispatcher("/forward" + pathInfo);
            dispatcher.forward(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
            return;
        }

        if (request.getDispatcherType() == DispatcherType.FORWARD)
        {
            Supplier<String> getServletPath = request::getServletPath;
            request.setAttribute("forward.getServletPath", getServletPath);
            request.setAttribute("forward.servletPath", getServletPath.get());
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String pathInfo = request.getPathInfo();
        if (pathInfo == null)
            pathInfo = "/pathInfo";
        RequestDispatcher dispatcher = getServletContext().getRequestDispatcher("/include" + pathInfo);
        dispatcher.include(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));

        String forwardServletPath = (String)request.getAttribute("forward.servletPath");
        @SuppressWarnings("unchecked")
        String forwardServletPathAfter = ((Supplier<String>)request.getAttribute("forward.getServletPath")).get();
        if (!forwardServletPath.startsWith("/forward"))
            throw new IllegalStateException("Wrong forward dispatch servlet path");
        if (!forwardServletPath.equals(forwardServletPathAfter))
            throw new IllegalStateException("Non durable forward dispatch servlet path: " + forwardServletPath + " != " + forwardServletPathAfter);

        String includeServletPath = (String)request.getAttribute("include.servletPath");
        @SuppressWarnings("unchecked")
        String includeServletPathAfter = ((Supplier<String>)request.getAttribute("include.getServletPath")).get();
        if (!includeServletPath.startsWith("/include"))
            throw new IllegalStateException("Wrong include dispatch servlet path");
        if (!includeServletPath.equals(includeServletPathAfter))
            throw new IllegalStateException("Non durable include dispatch servlet path: " + includeServletPath + " != " + includeServletPathAfter);

        response.getOutputStream().println("OK");
        response.getOutputStream().println(request.getServletPath() + " -> " + includeServletPath + " -> " + forwardServletPath);
    }

    @Override
    public String getServletInfo()
    {
        return "DurableDispatchServlet";
    }
}
