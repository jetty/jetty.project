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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;

class CrossContextDispatcher implements RequestDispatcher
{
    public static final String ORIGINAL_URI = "org.eclipse.jetty.dispatch.originalURI";
    public static final String ORIGINAL_QUERY_STRING = "org.eclipse.jetty.dispatch.originalQueryString";
    public static final String ORIGINAL_SERVLET_MAPPING = "org.eclipse.jetty.dispatch.originalServletMapping";
    public static final String ORIGINAL_CONTEXT_PATH = "org.eclipse.jetty.dispatch.originalContextPath";

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
        "javax.servlet.include.request_uri",
        "javax.servlet.include.mapping",
        "javax.servlet.include.context_path",
        "javax.servlet.include.servlet_path",
        "javax.servlet.include.query_string",
        "javax.servlet.include.path_info",
        ServletContextRequest.MULTIPART_CONFIG_ELEMENT,
        ContextHandler.CROSS_CONTEXT_ATTRIBUTE,
        ORIGINAL_URI,
        ORIGINAL_QUERY_STRING,
        ORIGINAL_SERVLET_MAPPING,
        ORIGINAL_CONTEXT_PATH
    );

    private final CrossContextServletContext _targetContext;
    private final HttpURI _uri;
    private final String _decodedPathInContext;

    private class IncludeRequest extends ServletCoreRequest
    {
        public IncludeRequest(HttpServletRequest httpServletRequest)
        {
            super(httpServletRequest, new Attributes.Synthetic(new ServletAttributes(httpServletRequest))
            {
                @Override
                public Object getAttribute(String name)
                {
                    //handle cross-environment dispatch from ee8
                    if (name.startsWith("javax.servlet."))
                        name = "jakarta.servlet." + name.substring(14);

                    return super.getAttribute(name);
                }

                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    if (name == null)
                        return null;

                    //Servlet Spec 9.3.1 no include attributes if a named dispatcher
/*                    if (_namedServlet != null && name.startsWith(Dispatcher.__INCLUDE_PREFIX))
                        return null;*/

                    //Special include attributes refer to the target context and path
                    return switch (name)
                    {
                        case RequestDispatcher.INCLUDE_MAPPING -> null;
                        case RequestDispatcher.INCLUDE_SERVLET_PATH -> null;
                        case RequestDispatcher.INCLUDE_PATH_INFO ->  _decodedPathInContext;
                        case RequestDispatcher.INCLUDE_REQUEST_URI -> (_uri == null) ? null : _uri.getPath();
                        case RequestDispatcher.INCLUDE_CONTEXT_PATH -> _targetContext.getContextPath();
                        case RequestDispatcher.INCLUDE_QUERY_STRING -> (_uri == null) ? null : _uri.getQuery();
                        case ContextHandler.CROSS_CONTEXT_ATTRIBUTE -> DispatcherType.INCLUDE.toString();

                        case ORIGINAL_URI -> httpServletRequest.getRequestURI();
                        case ORIGINAL_QUERY_STRING -> httpServletRequest.getQueryString();
                        case ORIGINAL_SERVLET_MAPPING -> httpServletRequest.getHttpServletMapping();
                        case ORIGINAL_CONTEXT_PATH -> httpServletRequest.getContextPath();

                        default -> httpServletRequest.getAttribute(name);
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }

                @Override
                public Object setAttribute(String name, Object attribute)
                {
                    if (name == null)
                        return null;

                    //handle cross-environment dispatch from ee8
                    if (name.startsWith("javax.servlet."))
                        name = "jakarta.servlet." + name.substring(14);

                    return super.setAttribute(name, attribute);
                }
            });
        }

        @Override
        public HttpURI getHttpURI()
        {
            //return the uri of the dispatch target
            return _uri;
        }
    }

    private class IncludeResponse extends ServletCoreResponse
    {
        public IncludeResponse(Request coreRequest, HttpServletResponse httpServletResponse)
        {
            super(coreRequest, httpServletResponse, true);
        }
    }

    private class AsyncRequest extends ServletCoreRequest
    {
        private final HttpURI _fullyQualifiedURI;

        public AsyncRequest(HttpServletRequest httpServletRequest)
        {
            super(httpServletRequest, new ServletAttributes(httpServletRequest));
            _fullyQualifiedURI = HttpURI.build(httpServletRequest.getRequestURL().toString()).pathQuery(_uri.getPathQuery()).asImmutable();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _fullyQualifiedURI;
        }
    }

    private class ForwardRequest extends ServletCoreRequest
    {
         private final HttpURI _fullyQualifiedURI;

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
                    if (name == null)
                        return null;

                    //handle cross-environment dispatch from ee8
                    if (name.startsWith("javax.servlet."))
                        name = "jakarta.servlet." + name.substring(14);

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
                        //TODO
                        //case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> httpServletRequest.getAttribute(ServletMultiPartFormData.class.getName());
                        case ContextHandler.CROSS_CONTEXT_ATTRIBUTE -> DispatcherType.FORWARD.toString();
                        default -> null;
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            });
            _fullyQualifiedURI = HttpURI.build(httpServletRequest.getRequestURL().toString()).pathQuery(_uri.getPathQuery()).asImmutable();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _fullyQualifiedURI;
        }
    }

    CrossContextDispatcher(CrossContextServletContext targetContext, HttpURI uri, String decodedPathInContext)
    {
        _targetContext = targetContext;
        _uri = uri;
        _decodedPathInContext = decodedPathInContext;
    }

    public void async(ServletRequest servletRequest, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpServletRequest = (servletRequest instanceof HttpServletRequest) ? ((HttpServletRequest)servletRequest) : new ServletRequestHttpWrapper(servletRequest);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        AsyncRequest asyncRequest = new AsyncRequest(httpServletRequest);
        // We must block when do a cross context async dispatch, as we cannot combine the state machines of the servletChannel from the source context and that of the target
        try (Blocker.Callback callback = Blocker.callback())
        {
            _targetContext.getTargetContext().getContextHandler().handle(asyncRequest, new ServletCoreResponse(asyncRequest, httpResponse, false), callback);
            callback.block();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

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

    @Override
    public void include(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException
    {
        HttpServletRequest httpServletRequest = (servletRequest instanceof HttpServletRequest) ? ((HttpServletRequest)servletRequest) : new ServletRequestHttpWrapper(servletRequest);
        HttpServletResponse httpServletResponse = (servletResponse instanceof HttpServletResponse) ? (HttpServletResponse)servletResponse : new ServletResponseHttpWrapper(servletResponse);
        ServletContextResponse servletContextResponse = ServletContextResponse.getServletContextResponse(servletResponse);

        IncludeRequest includeRequest = new IncludeRequest(httpServletRequest);
        IncludeResponse includeResponse = new IncludeResponse(includeRequest, httpServletResponse);

        try (Blocker.Callback callback = Blocker.callback())
        {
            _targetContext.getTargetContext().getContextHandler().handle(includeRequest, includeResponse, callback);
            callback.block();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }
}
