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
    private final String _requestURI;
    private final String _contextPath;
    private final String _pathInContext;
    private ServletPathMapping _mapping;
    private final String _queryString;

    public AsyncAttributes(Attributes attributes, String requestUri, String contextPath, String pathInContext, ServletPathMapping mapping, String queryString)
    {
        super(attributes);

        // TODO: make fields final in jetty-10 and NOOP when one of these attributes is set.
        _requestURI = requestUri;
        _contextPath = contextPath;
        _pathInContext = pathInContext;
        _mapping = mapping;
        _queryString = queryString;
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
                return _mapping == null ? null : _mapping.getServletPath();
            case AsyncContext.ASYNC_PATH_INFO:
                return _mapping == null ? _pathInContext : _mapping.getPathInfo();
            case AsyncContext.ASYNC_QUERY_STRING:
                return _queryString;
            case AsyncContext.ASYNC_MAPPING:
                return _mapping;
            default:
                return super.getAttribute(key);
        }
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        Set<String> set = new HashSet<>(super.getAttributeNameSet());
        set.add(AsyncContext.ASYNC_REQUEST_URI);
        set.add(AsyncContext.ASYNC_CONTEXT_PATH);
        set.add(AsyncContext.ASYNC_SERVLET_PATH);
        set.add(AsyncContext.ASYNC_PATH_INFO);
        set.add(AsyncContext.ASYNC_QUERY_STRING);
        set.add(AsyncContext.ASYNC_MAPPING);
        return set;
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        switch (key)
        {
            case AsyncContext.ASYNC_REQUEST_URI:
            case AsyncContext.ASYNC_CONTEXT_PATH:
            case AsyncContext.ASYNC_SERVLET_PATH:
            case AsyncContext.ASYNC_PATH_INFO:
            case AsyncContext.ASYNC_QUERY_STRING:
            case AsyncContext.ASYNC_MAPPING:
                break;
            default:
                super.setAttribute(key, value);
                break;
        }
    }

    public static void applyAsyncAttributes(Attributes attributes, String requestURI, String contextPath, String servletPath, String pathInfo, String queryString, HttpServletMapping httpServletMapping)
    {
        attributes.setAttribute(AsyncContext.ASYNC_REQUEST_URI, requestURI);
        attributes.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, contextPath);
        attributes.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, servletPath);
        attributes.setAttribute(AsyncContext.ASYNC_PATH_INFO, pathInfo);
        attributes.setAttribute(AsyncContext.ASYNC_QUERY_STRING, queryString);
        attributes.setAttribute(AsyncContext.ASYNC_MAPPING, httpServletMapping);
    }
}
