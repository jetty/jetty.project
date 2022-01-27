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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Servlet handling JSP Property Group mappings
 * <p>
 * This servlet is mapped to by any URL pattern for a JSP property group.
 * Resources handled by this servlet that are not directories will be passed
 * directly to the JSP servlet.    Resources that are directories will be
 * passed directly to the default servlet.
 */
public class JspPropertyGroupServlet extends GenericServlet
{
    private static final long serialVersionUID = 3681783214726776945L;

    public static final String NAME = "__org.eclipse.jetty.servlet.JspPropertyGroupServlet__";
    private final ServletHandler _servletHandler;
    private final ContextHandler _contextHandler;
    private ServletHolder _dftServlet;
    private ServletHolder _jspServlet;
    private boolean _starJspMapped;

    public JspPropertyGroupServlet(ContextHandler context, ServletHandler servletHandler)
    {
        _contextHandler = context;
        _servletHandler = servletHandler;
    }

    @Override
    public void init() throws ServletException
    {
        String jspName = "jsp";
        ServletMapping servletMapping = _servletHandler.getServletMapping("*.jsp");
        if (servletMapping != null)
        {
            _starJspMapped = true;

            //now find the jsp servlet, ignoring the mapping that is for ourself
            ServletMapping[] mappings = _servletHandler.getServletMappings();
            for (ServletMapping m : mappings)
            {
                String[] paths = m.getPathSpecs();
                if (paths != null)
                {
                    for (String path : paths)
                    {
                        if ("*.jsp".equals(path) && !NAME.equals(m.getServletName()))
                            servletMapping = m;
                    }
                }
            }

            jspName = servletMapping.getServletName();
        }
        _jspServlet = _servletHandler.getServlet(jspName);

        String defaultName = "default";
        ServletMapping defaultMapping = _servletHandler.getServletMapping("/");
        if (defaultMapping != null)
            defaultName = defaultMapping.getServletName();
        _dftServlet = _servletHandler.getServlet(defaultName);
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        HttpServletRequest request = null;
        if (req instanceof HttpServletRequest)
            request = (HttpServletRequest)req;
        else
            throw new ServletException("Request not HttpServletRequest");

        String servletPath = null;
        String pathInfo = null;
        if (request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI) != null)
        {
            servletPath = (String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH);
            pathInfo = (String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO);
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

        String pathInContext = URIUtil.addPaths(servletPath, pathInfo);

        if (pathInContext.endsWith("/"))
        {
            _dftServlet.getServlet().service(req, res);
        }
        else if (_starJspMapped && pathInContext.toLowerCase(Locale.ENGLISH).endsWith(".jsp"))
        {
            _jspServlet.getServlet().service(req, res);
        }
        else
        {

            Resource resource = _contextHandler.getResource(pathInContext);
            if (resource != null && resource.isDirectory())
                _dftServlet.getServlet().service(req, res);
            else
                _jspServlet.getServlet().service(req, res);
        }
    }
}
