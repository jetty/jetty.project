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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The {@code DefaultServlet}, is a specialization of the {@link ResourceServlet} to be mapped to {@code /} as the "default"
 * servlet for a context.
 * </p>
 * <p>
 * In addition to the servlet init parameters that can be used to configure any {@link ResourceServlet}, the DefaultServlet
 * also looks at {@link ServletContext#getInitParameter(String)} for any parameter starting with {@link #CONTEXT_INIT}, which
 * is then stripped and the resulting name interpreted as a {@link ResourceServlet} init parameter.
 * </p>
 * <p>
 * To serve static content other than as the {@code DefaultServlet} mapped to "/", please use the {@link ResourceServlet} directly.
 * The {@code DefaultServlet} will warn if it is used other than as the default servlet. In future, this may become a fatal error.
 * </p>
 */
public class DefaultServlet extends ResourceServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);
    public static final String CONTEXT_INIT = "org.eclipse.jetty.servlet.Default.";
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /**
     * <p>
     *     Returns a {@code String} containing the value of the named initialization parameter, or null if the parameter does not exist.
     * </p>
     *
     * <p>
     *     Parameter lookup first checks the {@link ServletContext#getInitParameter(String)} for the
     *     parameter prefixed with {@code org.eclipse.jetty.servlet.Default.}, then checks
     *     {@link jakarta.servlet.ServletConfig#getInitParameter(String)} for the actual value
     * </p>
     *
     * @param name a {@code String} specifying the name of the initialization parameter
     * @return a {@code String} containing the value of the initialization parameter
     */
    @Override
    public String getInitParameter(String name)
    {
        String value = getServletContext().getInitParameter(CONTEXT_INIT + name);
        if (value == null)
            value = super.getInitParameter(name);
        return value;
    }

    @Override
    public void init() throws ServletException
    {
        if ("true".equalsIgnoreCase(getInitParameter("pathInfoOnly")))
            LOG.warn("DefaultServlet pathInfoOnly is set to true. Use ResourceServlet instead.");
        super.init();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getDispatcherType() == DispatcherType.ERROR)
            doGet(request, response);
        else
            super.doPost(request, response);
    }

    /**
     * Get the path in the context, of the resource to serve for a request.
     * The default implementation considers the {@link jakarta.servlet.http.HttpServletMapping} of the request and
     * any {@link Dispatcher#INCLUDE_SERVLET_PATH} attributes that may be set.
     * @param request The request
     * @param included {@code true} if the request is for an included resource
     * @return The encoded URI path of the resource to server, relative to the resource base.
     */
    @Override
    protected String getEncodedPathInContext(HttpServletRequest request, boolean included)
    {
        String deprecatedPath =  getEncodedPathInContext(request, (String)(included ? request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH) : null));
        if (deprecatedPath != null)
            return deprecatedPath;

        if (request.getHttpServletMapping().getMappingMatch() != MappingMatch.DEFAULT)
        {
            if (warned.compareAndSet(false, true))
                LOG.warn("Incorrect mapping for DefaultServlet at %s. Use ResourceServlet".formatted(request.getHttpServletMapping().getPattern()));
            return super.getEncodedPathInContext(request, included);
        }

        if (included)
        {
            if (request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH) instanceof String servletPath)
                return URIUtil.encodePath(servletPath);

            // must be an include of a named dispatcher.  Just use the whole URI
            return URIUtil.encodePath(request.getServletPath());
        }

        if (request instanceof ServletApiRequest apiRequest)
            // Strip the context path from the canonically encoded path, so no need to re-encode (and mess up %2F etc.)
            return Context.getPathInContext(request.getContextPath(), apiRequest.getRequest().getHttpURI().getCanonicalPath());

        return URIUtil.encodePath(request.getServletPath());
    }

    @Deprecated(forRemoval = true)
    protected String getEncodedPathInContext(HttpServletRequest req, String includedServletPath)
    {
        return null;
    }
}
