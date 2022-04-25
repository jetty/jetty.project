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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee10.servlet.util.ServletOutputStreamWrapper;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Dispatcher implements RequestDispatcher
{
    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    /**
     * Dispatch include attribute names
     */
    public static final String __INCLUDE_PREFIX = "jakarta.servlet.include.";

    /**
     * Dispatch include attribute names
     */
    public static final String __FORWARD_PREFIX = "jakarta.servlet.forward.";

    private final ServletContextHandler _contextHandler;
    private final HttpURI _uri;
    private final String _pathInContext;
    private final String _named;
    private final ServletHandler.MappedServlet _mappedServlet;
    private final ServletHandler _servletHandler;
    private final ServletPathMapping _servletPathMapping;

    public Dispatcher(ServletContextHandler contextHandler, HttpURI uri, String pathInContext)
    {
        _contextHandler = contextHandler;
        _uri = uri.asImmutable();
        _pathInContext = pathInContext;
        _named = null;

        _servletHandler = _contextHandler.getServletHandler();
        _mappedServlet = _servletHandler.getMappedServlet(pathInContext);
        _servletPathMapping = _mappedServlet.getServletPathMapping(_pathInContext);
    }

    public Dispatcher(ServletContextHandler contextHandler, String name) throws IllegalStateException
    {
        _contextHandler = contextHandler;
        _uri = null;
        _pathInContext = null;
        _named = name;

        _servletHandler = _contextHandler.getServletHandler();
        _mappedServlet = _servletHandler.getMappedServlet(name);
        _servletPathMapping = null;
    }

    public void error(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        _mappedServlet.handle(_servletHandler, _pathInContext, new ErrorRequest(httpRequest), httpResponse);
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        ServletContextRequest baseRequest = Objects.requireNonNull(ServletContextRequest.getBaseRequest(request));
        baseRequest.getResponse().resetForForward();
        _mappedServlet.handle(_servletHandler, _pathInContext, new ForwardRequest(httpRequest), httpResponse);

        // If we are not async and not closed already, then close via the possibly wrapped response.
        if (!baseRequest.getState().isAsync() && !baseRequest.getHttpOutput().isClosed())
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

    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        _mappedServlet.handle(_servletHandler, _pathInContext, new IncludeRequest(httpRequest), new IncludeResponse(httpResponse));
    }

    public void async(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        _mappedServlet.handle(_servletHandler, _pathInContext, new AsyncRequest(httpRequest), httpResponse);
    }

    public class ParameterRequestWrapper extends HttpServletRequestWrapper
    {
        private Map<String, String[]> params;

        public ParameterRequestWrapper(HttpServletRequest request)
        {
            super(request);
        }

        @Override
        public String getParameter(String name)
        {
            String[] strings = getParameterMap().get(name);
            if (strings == null || strings.length == 0)
                return null;
            return strings[0];
        }

        @Override
        public Map<String, String[]> getParameterMap()
        {
            if (params != null)
                return params;

            Map<String, String[]> oldParams = super.getParameterMap();
            if (_uri == null || _uri.getQuery() == null)
            {
                params = oldParams;
                return oldParams;
            }

            MultiMap<String> newParams = new MultiMap<>();
            UrlEncoded.decodeTo(_uri.getQuery(), newParams, UrlEncoded.ENCODING);
            for (Map.Entry<String, String[]> entry : oldParams.entrySet())
            {
                newParams.addValues(entry.getKey(), entry.getValue());
            }
            params = newParams.toStringArrayMap();
            return params;
        }

        @Override
        public Enumeration<String> getParameterNames()
        {
            return Collections.enumeration(getParameterMap().entrySet().stream()
                .flatMap(o -> Arrays.stream(o.getValue()))
                .collect(Collectors.toList()));
        }

        @Override
        public String[] getParameterValues(String name)
        {
            return getParameterMap().get(name);
        }
    }

    private class ForwardRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public ForwardRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.FORWARD;
        }

        @Override
        public String getPathInfo()
        {
            return _servletPathMapping.getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            return _servletPathMapping.getServletPath();
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            return _servletPathMapping;
        }

        @Override
        public String getQueryString()
        {
            if (_uri != null)
            {
                String targetQuery = _uri.getQuery();
                if (!StringUtil.isEmpty(targetQuery))
                    return targetQuery;
            }
            return _httpServletRequest.getQueryString();
        }

        @Override
        public String getRequestURI()
        {
            return _uri == null ? null : _uri.getPath();
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case RequestDispatcher.FORWARD_REQUEST_URI:
                    return _httpServletRequest.getRequestURI();
                case RequestDispatcher.FORWARD_SERVLET_PATH:
                    return _httpServletRequest.getServletPath();
                case RequestDispatcher.FORWARD_PATH_INFO:
                    return _httpServletRequest.getPathInfo();
                case RequestDispatcher.FORWARD_CONTEXT_PATH:
                    return _httpServletRequest.getContextPath();
                case RequestDispatcher.FORWARD_MAPPING:
                    return _httpServletRequest.getHttpServletMapping();
                case RequestDispatcher.FORWARD_QUERY_STRING:
                    return _httpServletRequest.getQueryString();
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            names.add(RequestDispatcher.FORWARD_REQUEST_URI);
            names.add(RequestDispatcher.FORWARD_SERVLET_PATH);
            names.add(RequestDispatcher.FORWARD_PATH_INFO);
            names.add(RequestDispatcher.FORWARD_CONTEXT_PATH);
            names.add(RequestDispatcher.FORWARD_MAPPING);
            names.add(RequestDispatcher.FORWARD_QUERY_STRING);
            return Collections.enumeration(names);
        }
    }

    private class IncludeRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public IncludeRequest(HttpServletRequest request)
        {
            super(request);
            _httpServletRequest = request;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.INCLUDE;
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case RequestDispatcher.INCLUDE_MAPPING:
                    return _mappedServlet.getServletPathMapping(_pathInContext);
                case RequestDispatcher.INCLUDE_SERVLET_PATH:
                    return _mappedServlet.getServletPathMapping(_pathInContext).getServletPath();
                case RequestDispatcher.INCLUDE_PATH_INFO:
                    return _mappedServlet.getServletPathMapping(_pathInContext).getPathInfo();
                case RequestDispatcher.INCLUDE_REQUEST_URI:
                    return (_uri == null) ? null : _uri.getPath();
                case RequestDispatcher.INCLUDE_CONTEXT_PATH:
                    return _httpServletRequest.getContextPath();
                case RequestDispatcher.INCLUDE_QUERY_STRING:
                    return (_uri == null) ? null : _uri.getQuery();
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            names.add(RequestDispatcher.INCLUDE_MAPPING);
            names.add(RequestDispatcher.INCLUDE_SERVLET_PATH);
            names.add(RequestDispatcher.INCLUDE_PATH_INFO);
            names.add(RequestDispatcher.INCLUDE_REQUEST_URI);
            names.add(RequestDispatcher.INCLUDE_CONTEXT_PATH);
            names.add(RequestDispatcher.INCLUDE_QUERY_STRING);
            return Collections.enumeration(names);
        }
    }

    private static class IncludeResponse extends HttpServletResponseWrapper
    {
        public IncludeResponse(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException
        {
            return new ServletOutputStreamWrapper(getResponse().getOutputStream())
            {
                @Override
                public void close() throws IOException
                {
                    // NOOP for include.
                }
            };
        }

        @Override
        public void setCharacterEncoding(String charset)
        {
            // NOOP for include.
        }

        @Override
        public void setContentLength(int len)
        {
            // NOOP for include.
        }

        @Override
        public void setContentLengthLong(long len)
        {
            // NOOP for include.
        }

        @Override
        public void setContentType(String type)
        {
            // NOOP for include.
        }

        @Override
        public void reset()
        {
            // TODO can include do this?
            super.reset();
        }

        @Override
        public void resetBuffer()
        {
            // TODO can include do this?
            super.resetBuffer();
        }

        @Override
        public void setDateHeader(String name, long date)
        {
            // NOOP for include.
        }

        @Override
        public void addDateHeader(String name, long date)
        {
            // NOOP for include.
        }

        @Override
        public void setHeader(String name, String value)
        {
            // NOOP for include.
        }

        @Override
        public void addHeader(String name, String value)
        {
            // NOOP for include.
        }

        @Override
        public void setIntHeader(String name, int value)
        {
            // NOOP for include.
        }

        @Override
        public void addIntHeader(String name, int value)
        {
            // NOOP for include.
        }

        @Override
        public void setStatus(int sc)
        {
            // NOOP for include.
        }
    }

    private class AsyncRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public AsyncRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.ASYNC;
        }

        @Override
        public String getPathInfo()
        {
            return _mappedServlet.getServletPathMapping(_pathInContext).getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            return _mappedServlet.getServletPathMapping(_pathInContext).getServletPath();
        }

        @Override
        public String getQueryString()
        {
            if (_uri != null)
            {
                String targetQuery = _uri.getQuery();
                if (!StringUtil.isEmpty(targetQuery))
                    return targetQuery;
            }
            return _httpServletRequest.getQueryString();
        }

        @Override
        public String getRequestURI()
        {
            return _uri == null ? null : _uri.getPath();
        }

        @Override
        public StringBuffer getRequestURL()
        {
            return _uri == null ? null : new StringBuffer(_uri.asString());
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case AsyncContextState.ASYNC_REQUEST_URI:
                    return _httpServletRequest.getRequestURI();
                case AsyncContextState.ASYNC_CONTEXT_PATH:
                    return _httpServletRequest.getContextPath();
                case AsyncContextState.ASYNC_MAPPING:
                    return _httpServletRequest.getHttpServletMapping();
                case AsyncContextState.ASYNC_PATH_INFO:
                    return _httpServletRequest.getPathInfo();
                case AsyncContextState.ASYNC_SERVLET_PATH:
                    return _httpServletRequest.getServletPath();
                case AsyncContextState.ASYNC_QUERY_STRING:
                    return _httpServletRequest.getQueryString();
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            names.add(RequestDispatcher.FORWARD_REQUEST_URI);
            names.add(RequestDispatcher.FORWARD_SERVLET_PATH);
            names.add(RequestDispatcher.FORWARD_PATH_INFO);
            names.add(RequestDispatcher.FORWARD_CONTEXT_PATH);
            names.add(RequestDispatcher.FORWARD_MAPPING);
            names.add(RequestDispatcher.FORWARD_QUERY_STRING);
            return Collections.enumeration(names);
        }
    }

    // TODO
    private class ErrorRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public ErrorRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.ERROR;
        }

        @Override
        public String getPathInfo()
        {
            return _mappedServlet.getServletPathMapping(_pathInContext).getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            return _mappedServlet.getServletPathMapping(_pathInContext).getServletPath();
        }

        @Override
        public String getQueryString()
        {
            // TODO
            if (_uri != null)
            {
                String targetQuery = _uri.getQuery();
                if (!StringUtil.isEmpty(targetQuery))
                    return targetQuery;
            }
            return _httpServletRequest.getQueryString();
        }

        @Override
        public String getRequestURI()
        {
            return _uri == null ? null : _uri.getPath();
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                // TODO
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            // TODO
            return Collections.enumeration(names);
        }
    }

    @Override
    public String toString()
    {
        return String.format("Dispatcher@0x%x{%s,%s}", hashCode(), _named, _uri);
    }
}
