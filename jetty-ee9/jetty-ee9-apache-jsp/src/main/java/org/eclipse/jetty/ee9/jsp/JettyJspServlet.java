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

package org.eclipse.jetty.ee9.jsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jasper.servlet.JspServlet;

/**
 * JettyJspServlet
 *
 * Wrapper for the jsp servlet that handles receiving requests mapped from
 * jsp-property-groups. Mappings could be wildcard urls like "/*", which would
 * include welcome files, but we need those to be handled by the DefaultServlet.
 */
public class JettyJspServlet extends JspServlet
{

    /**
     *
     */
    private static final long serialVersionUID = -5387857473125086791L;

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        HttpServletRequest request = null;
        if (req instanceof HttpServletRequest)
            request = (HttpServletRequest)req;
        else
            throw new ServletException("Request not HttpServletRequest");

        String servletPath = null;
        String pathInfo = null;
        if (request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null)
        {
            servletPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            pathInfo = (String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            if (servletPath == null)
            {
                servletPath = request.getServletPath();
                pathInfo = request.getPathInfo();
            }
        }
        else
        {
            servletPath = request.getServletPath();
            pathInfo = request.getPathInfo();
        }

        String pathInContext = addPaths(servletPath, pathInfo);

        String jspFile = getInitParameter("jspFile");

        //if this is a forced-path from a jsp-file, we want the jsp servlet to handle it,
        //otherwise the default servlet might handle it
        if (jspFile == null)
        {
            if (pathInContext != null && pathInContext.endsWith("/"))
            {
                //dispatch via forward to the default servlet
                getServletContext().getNamedDispatcher("default").forward(req, resp);
                return;
            }
            else
            {
                //check if it resolves to a directory
                String realPath = getServletContext().getRealPath(pathInContext);
                if (realPath != null)
                {
                    Path asPath = Paths.get(realPath);
                    if (Files.exists(asPath) && Files.isDirectory(asPath))
                    {
                        //dispatch via forward to the default servlet
                        getServletContext().getNamedDispatcher("default").forward(req, resp);
                        return;
                    }
                }
            }
        }

        //fall through to the normal jsp servlet handling
        super.service(req, resp);
    }

    /**
     * @param servletPath the servletPath of the request
     * @param pathInfo the pathInfo of the request
     * @return servletPath with pathInfo appended
     */
    private String addPaths(String servletPath, String pathInfo)
    {
        if (servletPath.isEmpty())
            return pathInfo;

        if (pathInfo == null)
            return servletPath;

        return servletPath + pathInfo;
    }
}
