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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic Servlet Invoker.
 * This servlet invokes anonymous servlets that have not been defined
 * in the web.xml or by other means. The first element of the pathInfo
 * of a request passed to the envoker is treated as a servlet name for
 * an existing servlet, or as a class name of a new servlet.
 * This servlet is normally mapped to /servlet/*
 * This servlet support the following initParams:
 * <PRE>
 * nonContextServlets       If false, the invoker can only load
 * servlets from the contexts classloader.
 * This is false by default and setting this
 * to true may have security implications.
 *
 * verbose                  If true, log dynamic loads
 *
 * *                        All other parameters are copied to the
 * each dynamic servlet as init parameters
 * </PRE>
 *
 * @version $Id: Invoker.java 4780 2009-03-17 15:36:08Z jesse $
 */
@SuppressWarnings("serial")
public class Invoker extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(Invoker.class);

    private ContextHandler _contextHandler;
    private ServletHandler _servletHandler;
    private ServletHandler.MappedServlet _invokerEntry;
    private Map<String, String> _parameters;
    private boolean _nonContextServlets;
    private boolean _verbose;

    @Override
    public void init()
    {
        ServletContext config = getServletContext();
        _contextHandler = ((ContextHandler.Context)config).getContextHandler();

        Handler handler = _contextHandler.getHandler();
        while (handler != null && !(handler instanceof ServletHandler) && (handler instanceof HandlerWrapper))
        {
            handler = ((HandlerWrapper)handler).getHandler();
        }
        _servletHandler = (ServletHandler)handler;
        Enumeration<String> e = getInitParameterNames();
        while (e.hasMoreElements())
        {
            String param = e.nextElement();
            String value = getInitParameter(param);
            String lvalue = value.toLowerCase(Locale.ENGLISH);
            if ("nonContextServlets".equals(param))
            {
                _nonContextServlets = value.length() > 0 && lvalue.startsWith("t");
            }
            if ("verbose".equals(param))
            {
                _verbose = value.length() > 0 && lvalue.startsWith("t");
            }
            else
            {
                if (_parameters == null)
                    _parameters = new HashMap<String, String>();
                _parameters.put(param, value);
            }
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        // Get the requested path and info
        boolean included = false;
        String servletPath = (String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH);
        if (servletPath == null)
            servletPath = request.getServletPath();
        else
            included = true;
        String pathInfo = (String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO);
        if (pathInfo == null)
            pathInfo = request.getPathInfo();

        // Get the servlet class
        String servlet = pathInfo;
        if (servlet == null || servlet.length() <= 1)
        {
            response.sendError(404);
            return;
        }

        int i0 = servlet.charAt(0) == '/' ? 1 : 0;
        int i1 = servlet.indexOf('/', i0);
        servlet = i1 < 0 ? servlet.substring(i0) : servlet.substring(i0, i1);

        // look for a named holder
        ServletHolder[] holders = _servletHandler.getServlets();
        ServletHolder holder = getHolder(holders, servlet);

        if (holder != null)
        {
            // Found a named servlet (from a user's web.xml file) so
            // now we add a mapping for it
            if (LOG.isDebugEnabled())
                LOG.debug("Adding servlet mapping for named servlet: {}:{}/*", servlet, URIUtil.addPaths(servletPath, servlet));
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet);
            mapping.setPathSpec(URIUtil.addPaths(servletPath, servlet) + "/*");
            _servletHandler.setServletMappings(ArrayUtil.addToArray(_servletHandler.getServletMappings(), mapping, ServletMapping.class));
        }
        else
        {
            // look for a class mapping
            if (servlet.endsWith(".class"))
                servlet = servlet.substring(0, servlet.length() - 6);
            if (servlet == null || servlet.length() == 0)
            {
                response.sendError(404);
                return;
            }

            try (AutoLock l = _servletHandler.lock())
            {
                // find the entry for the invoker (me)
                _invokerEntry = _servletHandler.getMappedServlet(servletPath);

                // Check for existing mapping (avoid threaded race).
                String path = URIUtil.addPaths(servletPath, servlet);
                ServletHandler.MappedServlet entry = _servletHandler.getMappedServlet(path);

                if (entry != null && !entry.equals(_invokerEntry))
                {
                    // Use the holder
                    holder = (ServletHolder)entry.getServletHolder();
                }
                else
                {
                    // Make a holder
                    if (LOG.isDebugEnabled())
                        LOG.debug("Making new servlet={}  with path={}/*", servlet, path);
                    holder = _servletHandler.addServletWithMapping(servlet, path + "/*");

                    if (_parameters != null)
                        holder.setInitParameters(_parameters);

                    try
                    {
                        holder.start();
                    }
                    catch (Exception e)
                    {
                        LOG.debug("Unable to start {}", holder, e);
                        throw new UnavailableException(e.toString());
                    }

                    // Check it is from an allowable classloader
                    if (!_nonContextServlets)
                    {
                        Object s = holder.getServlet();

                        if (_contextHandler.getClassLoader() !=
                            s.getClass().getClassLoader())
                        {
                            try
                            {
                                holder.stop();
                            }
                            catch (Exception e)
                            {
                                LOG.trace("IGNORED", e);
                            }

                            LOG.warn("Dynamic servlet {} not loaded from context {}", s, request.getContextPath());
                            throw new UnavailableException("Not in context");
                        }
                    }

                    if (_verbose && LOG.isDebugEnabled())
                        LOG.debug("Dynamic load '{}' at {}", servlet, path);
                }
            }
        }

        if (holder != null)
        {
            final Request baseRequest = Request.getBaseRequest(request);
            holder.prepare(baseRequest, request, response);
            holder.handle(baseRequest,
                new InvokedRequest(request, included, servlet, servletPath, pathInfo),
                response);
        }
        else
        {
            LOG.info("Can't find holder for servlet: {}", servlet);
            response.sendError(404);
        }
    }

    class InvokedRequest extends HttpServletRequestWrapper
    {
        String _servletPath;
        String _pathInfo;
        boolean _included;

        InvokedRequest(HttpServletRequest request,
                       boolean included,
                       String name,
                       String servletPath,
                       String pathInfo)
        {
            super(request);
            _included = included;
            _servletPath = URIUtil.addPaths(servletPath, name);
            _pathInfo = pathInfo.substring(name.length() + 1);
            if (_pathInfo.length() == 0)
                _pathInfo = null;
        }

        @Override
        public String getServletPath()
        {
            if (_included)
                return super.getServletPath();
            return _servletPath;
        }

        @Override
        public String getPathInfo()
        {
            if (_included)
                return super.getPathInfo();
            return _pathInfo;
        }

        @Override
        public Object getAttribute(String name)
        {
            if (_included)
            {
                if (name.equals(Dispatcher.INCLUDE_REQUEST_URI))
                    return URIUtil.addPaths(URIUtil.addPaths(getContextPath(), _servletPath), _pathInfo);
                if (name.equals(Dispatcher.INCLUDE_PATH_INFO))
                    return _pathInfo;
                if (name.equals(Dispatcher.INCLUDE_SERVLET_PATH))
                    return _servletPath;
            }
            return super.getAttribute(name);
        }
    }

    private ServletHolder getHolder(ServletHolder[] holders, String servlet)
    {
        if (holders == null)
            return null;

        ServletHolder holder = null;
        for (int i = 0; holder == null && i < holders.length; i++)
        {
            if (holders[i].getName().equals(servlet))
            {
                holder = holders[i];
            }
        }
        return holder;
    }
}
