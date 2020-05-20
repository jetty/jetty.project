//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Dispatcher implements RequestDispatcher
{
    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    /**
     * Dispatch include attribute names
     */
    public static final String __INCLUDE_PREFIX = "javax.servlet.include.";

    /**
     * Dispatch include attribute names
     */
    public static final String __FORWARD_PREFIX = "javax.servlet.forward.";

    private final ContextHandler _contextHandler;
    private final HttpURI _uri;
    private final String _pathInContext;
    private final String _named;

    public Dispatcher(ContextHandler contextHandler, HttpURI uri, String pathInContext)
    {
        _contextHandler = contextHandler;
        _uri = uri.asImmutable();
        _pathInContext = pathInContext;
        _named = null;
    }

    public Dispatcher(ContextHandler contextHandler, String name) throws IllegalStateException
    {
        _contextHandler = contextHandler;
        _uri = null;
        _pathInContext = null;
        _named = name;
    }

    public void error(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        forward(request, response, DispatcherType.ERROR);
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        Request baseRequest = Request.getBaseRequest(request);

        if (!(request instanceof HttpServletRequest))
            request = new ServletRequestHttpWrapper(request);
        if (!(response instanceof HttpServletResponse))
            response = new ServletResponseHttpWrapper(response);

        final DispatcherType old_type = baseRequest.getDispatcherType();
        final Attributes old_attr = baseRequest.getAttributes();
        final MultiMap<String> old_query_params = baseRequest.getQueryParameters();
        try
        {
            baseRequest.setDispatcherType(DispatcherType.INCLUDE);
            baseRequest.getResponse().include();
            if (_named != null)
            {
                _contextHandler.handle(_named, baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            }
            else
            {
                IncludeAttributes attr = new IncludeAttributes(old_attr, _uri.getPath(), _contextHandler.getContextPath(), _pathInContext, _uri.getQuery());
                if (attr._query != null)
                    baseRequest.mergeQueryParameters(baseRequest.getQueryString(), attr._query);
                baseRequest.setAttributes(attr);

                _contextHandler.handle(_pathInContext, baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            }
        }
        finally
        {
            baseRequest.setAttributes(old_attr);
            baseRequest.getResponse().included();
            baseRequest.setQueryParameters(old_query_params);
            baseRequest.resetParameters();
            baseRequest.setDispatcherType(old_type);
        }
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        forward(request, response, DispatcherType.FORWARD);
    }

    protected void forward(ServletRequest request, ServletResponse response, DispatcherType dispatch) throws ServletException, IOException
    {
        Request baseRequest = Request.getBaseRequest(request);
        Response baseResponse = baseRequest.getResponse();
        baseResponse.resetForForward();

        if (!(request instanceof HttpServletRequest))
            request = new ServletRequestHttpWrapper(request);
        if (!(response instanceof HttpServletResponse))
            response = new ServletResponseHttpWrapper(response);

        final HttpURI old_uri = baseRequest.getHttpURI();
        final String old_context_path = baseRequest.getContextPath();
        final String old_servlet_path = baseRequest.getServletPath();
        final String old_path_info = baseRequest.getPathInfo();
        final ServletPathMapping old_mapping = baseRequest.getServletPathMapping();

        final MultiMap<String> old_query_params = baseRequest.getQueryParameters();
        final Attributes old_attr = baseRequest.getAttributes();
        final DispatcherType old_type = baseRequest.getDispatcherType();

        try
        {
            baseRequest.setDispatcherType(dispatch);

            if (_named != null)
            {
                _contextHandler.handle(_named, baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            }
            else
            {
                // If we have already been forwarded previously, then keep using the established
                // original value. Otherwise, this is the first forward and we need to establish the values.
                // Note: the established value on the original request for pathInfo and
                // for queryString is allowed to be null, but cannot be null for the other values.
                // Note: the pathInfo is passed as the pathInContext since it is only used when there is
                // no mapping, and when there is no mapping the pathInfo is the pathInContext.
                // TODO Ultimately it is intended for the request to carry the pathInContext for easy access
                ForwardAttributes attr = old_attr.getAttribute(FORWARD_REQUEST_URI) != null
                    ? new ForwardAttributes(old_attr,
                    (String)old_attr.getAttribute(FORWARD_REQUEST_URI),
                    (String)old_attr.getAttribute(FORWARD_CONTEXT_PATH),
                    (String)old_attr.getAttribute(FORWARD_PATH_INFO),
                    (ServletPathMapping)old_attr.getAttribute(FORWARD_MAPPING),
                    (String)old_attr.getAttribute(FORWARD_QUERY_STRING))
                    : new ForwardAttributes(old_attr,
                    old_uri.getPath(),
                    old_context_path,
                    baseRequest.getPathInfo(), // TODO replace with pathInContext
                    old_mapping,
                    old_uri.getQuery());

                String query = _uri.getQuery();
                if (query == null)
                    query = old_uri.getQuery();

                baseRequest.setHttpURI(HttpURI.build(old_uri, _uri.getPath(), _uri.getParam(), query));
                baseRequest.setContextPath(_contextHandler.getContextPath());
                baseRequest.setServletPathMapping(null);
                baseRequest.setServletPath(null);
                baseRequest.setPathInfo(_pathInContext);

                if (_uri.getQuery() != null || old_uri.getQuery() != null)
                {
                    try
                    {
                        baseRequest.mergeQueryParameters(old_uri.getQuery(), _uri.getQuery());
                    }
                    catch (BadMessageException e)
                    {
                        // Only throw BME if not in Error Dispatch Mode
                        // This allows application ErrorPageErrorHandler to handle BME messages
                        if (dispatch != DispatcherType.ERROR)
                        {
                            throw e;
                        }
                        else
                        {
                            LOG.warn("Ignoring Original Bad Request Query String: " + old_uri, e);
                        }
                    }
                }

                baseRequest.setAttributes(attr);

                _contextHandler.handle(_pathInContext, baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);

                // If we are not async and not closed already, then close via the possibly wrapped response.
                if (!baseRequest.getHttpChannelState().isAsync() && !baseResponse.getHttpOutput().isClosed())
                {
                    try
                    {
                        response.getOutputStream().close();
                    }
                    catch (IllegalStateException e)
                    {
                        response.getWriter().close();
                    }
                }
            }
        }
        finally
        {
            baseRequest.setHttpURI(old_uri);
            baseRequest.setContextPath(old_context_path);
            baseRequest.setServletPath(old_servlet_path);
            baseRequest.setPathInfo(old_path_info);
            baseRequest.setQueryParameters(old_query_params);
            baseRequest.resetParameters();
            baseRequest.setAttributes(old_attr);
            baseRequest.setDispatcherType(old_type);
        }
    }

    @Override
    public String toString()
    {
        return String.format("Dispatcher@0x%x{%s,%s}", hashCode(), _named, _uri);
    }

    private class ForwardAttributes extends Attributes.Wrapper
    {
        private final String _requestURI;
        private final String _contextPath;
        private final String _pathInContext;
        private final ServletPathMapping _servletPathMapping;
        private final String _query;

        public ForwardAttributes(Attributes attributes, String requestURI, String contextPath, String pathInContext, ServletPathMapping mapping, String query)
        {
            super(attributes);
            _requestURI = requestURI;
            _contextPath = contextPath;
            _pathInContext = pathInContext;
            _servletPathMapping = mapping;
            _query = query;
        }

        @Override
        public Object getAttribute(String key)
        {
            if (Dispatcher.this._named == null)
            {
                switch (key)
                {
                    case FORWARD_PATH_INFO:
                        return _servletPathMapping == null ? _pathInContext : _servletPathMapping.getPathInfo();
                    case FORWARD_REQUEST_URI:
                        return _requestURI;
                    case FORWARD_SERVLET_PATH:
                        return _servletPathMapping == null ? null : _servletPathMapping.getServletPath();
                    case FORWARD_CONTEXT_PATH:
                        return _contextPath;
                    case FORWARD_QUERY_STRING:
                        return _query;
                    case FORWARD_MAPPING:
                        return _servletPathMapping;
                    default:
                        break;
                }
            }

            // If we are forwarded then we hide include attributes
            if (key.startsWith(__INCLUDE_PREFIX))
                return null;

            return _attributes.getAttribute(key);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            HashSet<String> set = new HashSet<>();
            for (String name : _attributes.getAttributeNameSet())
            {
                if (!name.startsWith(__INCLUDE_PREFIX) &&
                    !name.startsWith(__FORWARD_PREFIX))
                    set.add(name);
            }

            if (_named == null)
            {
                set.add(FORWARD_PATH_INFO);
                set.add(FORWARD_REQUEST_URI);
                set.add(FORWARD_SERVLET_PATH);
                set.add(FORWARD_CONTEXT_PATH);
                set.add(FORWARD_MAPPING);
                set.add(FORWARD_QUERY_STRING);
            }

            return set;
        }

        @Override
        public void setAttribute(String key, Object value)
        {
            // Allow any attribute to be set, even if a reserved name. If a reserved
            // name is set here, it will be hidden by this class during the forward,
            // but revealed after the forward is complete just as if the reserved name
            // attribute had be set by the application before the forward.
            _attributes.setAttribute(key, value);
        }

        @Override
        public String toString()
        {
            return "FORWARD+" + _attributes.toString();
        }

        @Override
        public void clearAttributes()
        {
            throw new IllegalStateException();
        }

        @Override
        public void removeAttribute(String name)
        {
            setAttribute(name, null);
        }
    }

    private class IncludeAttributes extends Attributes.Wrapper
    {
        private final String _requestURI;
        private final String _contextPath;
        private final String _pathInContext;
        private ServletPathMapping _servletPathMapping; // Set later by ServletHandler
        private final String _query;

        public IncludeAttributes(Attributes attributes, String requestURI, String contextPath, String pathInContext, String query)
        {
            super(attributes);
            _requestURI = requestURI;
            _contextPath = contextPath;
            _pathInContext = pathInContext;
            _query = query;
        }

        @Override
        public Object getAttribute(String key)
        {
            if (Dispatcher.this._named == null)
            {
                switch (key)
                {
                    case INCLUDE_PATH_INFO:
                        return _servletPathMapping == null ? _pathInContext : _servletPathMapping.getPathInfo();
                    case INCLUDE_SERVLET_PATH:
                        return _servletPathMapping == null ? null : _servletPathMapping.getServletPath();
                    case INCLUDE_CONTEXT_PATH:
                        return _contextPath;
                    case INCLUDE_QUERY_STRING:
                        return _query;
                    case INCLUDE_REQUEST_URI:
                        return _requestURI;
                    case INCLUDE_MAPPING:
                        return _servletPathMapping;
                    default:
                        break;
                }
            }

            return _attributes.getAttribute(key);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            HashSet<String> set = new HashSet<>();
            for (String name : _attributes.getAttributeNameSet())
            {
                if (!name.startsWith(__INCLUDE_PREFIX))
                    set.add(name);
            }

            if (_named == null)
            {
                set.add(INCLUDE_PATH_INFO);
                set.add(INCLUDE_REQUEST_URI);
                set.add(INCLUDE_SERVLET_PATH);
                set.add(INCLUDE_CONTEXT_PATH);
                set.add(INCLUDE_MAPPING);
                set.add(INCLUDE_QUERY_STRING);
            }

            return set;
        }

        @Override
        public void setAttribute(String key, Object value)
        {
            if (_servletPathMapping == null && _named == null && INCLUDE_MAPPING.equals(key))
                _servletPathMapping = (ServletPathMapping)value;
            else
                // Allow any attribute to be set, even if a reserved name. If a reserved
                // name is set here, it will be revealed after the include is complete.
                _attributes.setAttribute(key, value);
        }

        @Override
        public String toString()
        {
            return "INCLUDE+" + _attributes.toString();
        }

        @Override
        public void clearAttributes()
        {
            throw new IllegalStateException();
        }

        @Override
        public void removeAttribute(String name)
        {
            setAttribute(name, null);
        }
    }
}
