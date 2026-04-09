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

package org.eclipse.jetty.ee11.servlet;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * Servlet Context.
 * <p>
 * This extension to the ContextHandler allows for
 * simple construction of a context with ServletHandler and optionally
 * session and security handlers, et.
 * <pre>
 *   new ServletContext("/context",Context.SESSIONS|Context.NO_SECURITY);
 * </pre>
 * <p>
 * This class should have been called ServletContext, but this would have
 * cause confusion with {@link jakarta.servlet.ServletContext}.
 */
@ManagedObject("Servlet Context Handler")
public class ServletContextHandler extends org.eclipse.jetty.ee.servlet.ServletContextHandler
{
    public static ServletContextHandler getCurrentServletContextHandler()
    {
        Context context = ContextHandler.getCurrentContext();
        if (context instanceof ServletScopedContext servletScopedContext)
            return (ServletContextHandler)servletScopedContext.getServletContextHandler();
        return null;
    }

    public ServletContextHandler()
    {
        super();
    }

    public ServletContextHandler(String contextPath)
    {
        super(contextPath);
    }

    public ServletContextHandler(int options)
    {
        super(options);
    }

    public ServletContextHandler(String contextPath, int options)
    {
        super(contextPath, options);
    }

    public ServletContextHandler(String contextPath, boolean sessions, boolean security)
    {
        super(contextPath, sessions, security);
    }

    public ServletContextHandler(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public ServletContextHandler(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public ServletContextHandler(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
    }

    @Override
    public ServletHandler getServletHandler()
    {
        return (ServletHandler)super.getServletHandler();
    }

    @Override
    public SessionHandler getSessionHandler()
    {
        return (SessionHandler)super.getSessionHandler();
    }

    @Override
    public ServletHolder addServlet(String className, String pathSpec)
    {
        return (ServletHolder)super.addServlet(className, pathSpec);
    }

    @Override
    public ServletHolder addServlet(Class<? extends Servlet> servlet, String pathSpec)
    {
        return (ServletHolder)super.addServlet(servlet, pathSpec);
    }

    @Override
    public ServletHolder addServlet(HttpServlet servlet, String pathSpec)
    {
        return (ServletHolder)super.addServlet(servlet, pathSpec);
    }

    @Override
    public FilterHolder addFilter(String filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return (FilterHolder)super.addFilter(filterClass, pathSpec, dispatches);
    }

    @Override
    public FilterHolder addFilter(Filter filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return (FilterHolder)super.addFilter(filter, pathSpec, dispatches);
    }

    @Override
    protected org.eclipse.jetty.ee.servlet.SessionHandler newSessionHandler()
    {
        return new SessionHandler();
    }

    @Override
    protected org.eclipse.jetty.ee.servlet.ServletHandler newServletHandler()
    {
        return new ServletHandler();
    }
}
