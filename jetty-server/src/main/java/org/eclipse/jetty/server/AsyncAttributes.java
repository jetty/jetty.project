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

import java.util.HashSet;
import java.util.Set;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletMapping;

import org.eclipse.jetty.util.Attributes;

class AsyncAttributes extends Attributes.Wrapper
{
    private String _requestURI;
    private String _contextPath;
    private String _servletPath;
    private String _pathInfo;
    private String _queryString;
    private HttpServletMapping _httpServletMapping;

    public AsyncAttributes(Attributes attributes, String requestUri, String contextPath, String servletPath, String pathInfo, String queryString, HttpServletMapping httpServletMapping)
    {
        super(attributes);

        // TODO: make fields final in jetty-10 and NOOP when one of these attributes is set.
        _requestURI = requestUri;
        _contextPath = contextPath;
        _servletPath = servletPath;
        _pathInfo = pathInfo;
        _queryString = queryString;
        _httpServletMapping = httpServletMapping;
    }

    @Override
    public Object getAttribute(String key)
    {
        switch (key)
        {
            case AsyncContext.ASYNC_REQUEST_URI:
                return _requestURI;
            case AsyncContext.ASYNC_CONTEXT_PATH:
                return _contextPath;
            case AsyncContext.ASYNC_SERVLET_PATH:
                return _servletPath;
            case AsyncContext.ASYNC_PATH_INFO:
                return _pathInfo;
            case AsyncContext.ASYNC_QUERY_STRING:
                return _queryString;
            case AsyncContext.ASYNC_MAPPING:
                return _httpServletMapping;
            default:
                return super.getAttribute(key);
        }
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        Set<String> set = new HashSet<>(super.getAttributeNameSet());
        if (_requestURI != null)
            set.add(AsyncContext.ASYNC_REQUEST_URI);
        if (_contextPath != null)
            set.add(AsyncContext.ASYNC_CONTEXT_PATH);
        if (_servletPath != null)
            set.add(AsyncContext.ASYNC_SERVLET_PATH);
        if (_pathInfo != null)
            set.add(AsyncContext.ASYNC_PATH_INFO);
        if (_queryString != null)
            set.add(AsyncContext.ASYNC_QUERY_STRING);
        if (_httpServletMapping != null)
            set.add(AsyncContext.ASYNC_MAPPING);
        return set;
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        switch (key)
        {
            case AsyncContext.ASYNC_REQUEST_URI:
                _requestURI = (String)value;
                break;
            case AsyncContext.ASYNC_CONTEXT_PATH:
                _contextPath = (String)value;
                break;
            case AsyncContext.ASYNC_SERVLET_PATH:
                _servletPath = (String)value;
                break;
            case AsyncContext.ASYNC_PATH_INFO:
                _pathInfo = (String)value;
                break;
            case AsyncContext.ASYNC_QUERY_STRING:
                _queryString = (String)value;
                break;
            case AsyncContext.ASYNC_MAPPING:
                _httpServletMapping = (HttpServletMapping)value;
                break;
            default:
                super.setAttribute(key, value);
                break;
        }
    }

    @Override
    public void clearAttributes()
    {
        _requestURI = null;
        _contextPath = null;
        _servletPath = null;
        _pathInfo = null;
        _queryString = null;
        _httpServletMapping = null;
        super.clearAttributes();
    }

    public static void applyAsyncAttributes(Attributes attributes, String requestURI, String contextPath, String servletPath, String pathInfo, String queryString, HttpServletMapping httpServletMapping)
    {
        if (requestURI != null)
            attributes.setAttribute(AsyncContext.ASYNC_REQUEST_URI, requestURI);
        if (contextPath != null)
            attributes.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, contextPath);
        if (servletPath != null)
            attributes.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, servletPath);
        if (pathInfo != null)
            attributes.setAttribute(AsyncContext.ASYNC_PATH_INFO, pathInfo);
        if (queryString != null)
            attributes.setAttribute(AsyncContext.ASYNC_QUERY_STRING, queryString);
        if (httpServletMapping != null)
            attributes.setAttribute(AsyncContext.ASYNC_MAPPING, httpServletMapping);
    }
}
