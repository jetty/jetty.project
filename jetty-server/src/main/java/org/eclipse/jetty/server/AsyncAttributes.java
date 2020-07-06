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

import org.eclipse.jetty.util.Attributes;

class AsyncAttributes extends Attributes.Wrapper
{
    public static final String __ASYNC_PREFIX = "javax.servlet.async.";

    private final String _requestURI;
    private final String _contextPath;
    private final String _pathInContext;
    private final ServletPathMapping _mapping;
    private final String _queryString;

    public AsyncAttributes(Attributes attributes, String requestUri, String contextPath, String pathInContext, ServletPathMapping mapping, String queryString)
    {
        super(attributes);
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
        Set<String> set = new HashSet<>();
        super.getAttributeNameSet().stream()
            .filter(name -> !name.startsWith(__ASYNC_PREFIX))
            .forEach(set::add);

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
                // Ignore sets for these reserved names as this class is applied
                // we will always override these particular attributes.
                break;
            default:
                super.setAttribute(key, value);
                break;
        }
    }
}
