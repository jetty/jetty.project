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

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;

public class CrossContextDispatcher implements RequestDispatcher
{
    public static final Set<String> ATTRIBUTES = Set.of(
        RequestDispatcher.FORWARD_REQUEST_URI,
        RequestDispatcher.FORWARD_MAPPING,
        RequestDispatcher.FORWARD_CONTEXT_PATH,
        RequestDispatcher.FORWARD_SERVLET_PATH,
        RequestDispatcher.FORWARD_QUERY_STRING,
        RequestDispatcher.FORWARD_PATH_INFO,
        RequestDispatcher.INCLUDE_REQUEST_URI,
        RequestDispatcher.INCLUDE_MAPPING,
        RequestDispatcher.INCLUDE_CONTEXT_PATH,
        RequestDispatcher.INCLUDE_SERVLET_PATH,
        RequestDispatcher.INCLUDE_QUERY_STRING,
        RequestDispatcher.INCLUDE_PATH_INFO,
        ServletContextRequest.MULTIPART_CONFIG_ELEMENT
    );

    private final ServletContextHandler.DispatchableServletContextApi _targetContext;
    private final HttpURI _uri;
    private final String _decodedPathInContext;

    private final String _namedServlet = null;

    private class ForwardRequest extends ServletCoreRequest
    {
        /**
         * @param httpServletRequest the request to wrap
         */
        public ForwardRequest(HttpServletRequest httpServletRequest)
        {
            super(httpServletRequest, new Attributes.Synthetic(new ServletAttributes(httpServletRequest))
            {
                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    return switch (name)
                    {
                        case RequestDispatcher.FORWARD_REQUEST_URI -> httpServletRequest.getRequestURI();
                        case RequestDispatcher.FORWARD_SERVLET_PATH -> httpServletRequest.getServletPath();
                        case RequestDispatcher.FORWARD_PATH_INFO -> httpServletRequest.getPathInfo();
                        case RequestDispatcher.FORWARD_CONTEXT_PATH -> httpServletRequest.getContextPath();
                        case RequestDispatcher.FORWARD_MAPPING -> httpServletRequest.getHttpServletMapping();
                        case RequestDispatcher.FORWARD_QUERY_STRING -> httpServletRequest.getQueryString();
                        case RequestDispatcher.INCLUDE_MAPPING -> REMOVED;
                        case RequestDispatcher.INCLUDE_REQUEST_URI -> REMOVED;
                        case RequestDispatcher.INCLUDE_CONTEXT_PATH -> REMOVED;
                        case RequestDispatcher.INCLUDE_QUERY_STRING -> REMOVED;
                        case RequestDispatcher.INCLUDE_SERVLET_PATH -> REMOVED;
                        case RequestDispatcher.INCLUDE_PATH_INFO -> REMOVED;
                        case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> httpServletRequest.getAttribute(ServletMultiPartFormData.class.getName());
                        default -> null;
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            });
        }

        /**
         * @return 
         */
        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }
    }

    public CrossContextDispatcher(ServletContextHandler.DispatchableServletContextApi targetContext, HttpURI uri, String decodedPathInContext)
    {
        _targetContext = targetContext;
        _uri = uri;
        _decodedPathInContext = decodedPathInContext;
    }

    /**
     * @param servletRequest a {@link ServletRequest} object that represents the request the client makes of the servlet
     * @param response a {@link ServletResponse} object that represents the response the servlet returns to the client
     * @throws ServletException
     * @throws IOException
     */
    @Override
    public void forward(ServletRequest servletRequest, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpServletRequest = (servletRequest instanceof HttpServletRequest) ? ((HttpServletRequest)servletRequest) : new ServletRequestHttpWrapper(servletRequest);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(servletRequest);
        servletContextRequest.getServletContextResponse().resetForForward();

        ForwardRequest forwardRequest = new ForwardRequest(httpServletRequest);

        try (Blocker.Callback callback = Blocker.callback())
        {
            _targetContext.getTargetContext().getContextHandler().handle(forwardRequest, new ServletCoreResponse(forwardRequest, httpResponse, false), callback);
            callback.block();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }

        // If we are not async and not closed already, then close via the possibly wrapped response.
        if (!servletContextRequest.getState().isAsync() && !servletContextRequest.getServletContextResponse().hasLastWrite())
        {
            Closeable closeable;
            try
            {
                closeable = response.getOutputStream();
            }
            catch (IllegalStateException e)
            {
                closeable = response.getWriter();
            }
            IO.close(closeable);
        }
    }

    /**
     * @param request a {@link ServletRequest} object that contains the client's request
     * @param response a {@link ServletResponse} object that contains the servlet's response
     * @throws ServletException
     * @throws IOException
     */
    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        //TODO
    }
}
