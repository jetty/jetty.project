//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.AsyncContext;

import org.eclipse.jetty.util.Attributes;

class AsyncAttributes extends Attributes.Wrapper
{
    public static final String __ASYNC_PREFIX = "javax.servlet.async.";

    private String _requestURI;
    private String _contextPath;
    private String _servletPath;
    private String _pathInfo;
    private String _queryString;

    public AsyncAttributes(Attributes attributes, String requestUri, String contextPath, String servletPath, String pathInfo, String queryString)
    {
        super(attributes);

        // TODO: make fields final in jetty-10 and NOOP when one of these attributes is set.
        _requestURI = requestUri;
        _contextPath = contextPath;
        _servletPath = servletPath;
        _pathInfo = pathInfo;
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
                return _servletPath;
            case AsyncContext.ASYNC_PATH_INFO:
                return _pathInfo;
            case AsyncContext.ASYNC_QUERY_STRING:
                return _queryString;
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
        super.clearAttributes();
    }

    public static void applyAsyncAttributes(Attributes attributes, String requestURI, String contextPath, String servletPath, String pathInfo, String queryString)
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
    }
}
