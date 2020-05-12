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
    /**
     * Async dispatch attribute name prefix.
     */
    public static final String __ASYNC_PREFIX = "javax.servlet.async.";

    private String _requestURI;
    private String _contextPath;
    private String _servletPath;
    private String _pathInfo;
    private String _query;

    AsyncAttributes(Attributes attributes)
    {
        super(attributes);
    }

    @Override
    public Object getAttribute(String key)
    {
        if (!key.startsWith(__ASYNC_PREFIX))
            return super.getAttribute(key);

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
                return _query;
            default:
                return super.getAttribute(key);
        }
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        HashSet<String> set = new HashSet<>();
        for (String name : _attributes.getAttributeNameSet())
        {
            if (!name.startsWith(__ASYNC_PREFIX))
                set.add(name);
        }

        if (_requestURI != null)
            set.add(AsyncContext.ASYNC_REQUEST_URI);
        if (_contextPath != null)
            set.add(AsyncContext.ASYNC_CONTEXT_PATH);
        if (_servletPath != null)
            set.add(AsyncContext.ASYNC_SERVLET_PATH);
        if (_pathInfo != null)
            set.add(AsyncContext.ASYNC_PATH_INFO);
        if (_query != null)
            set.add(AsyncContext.ASYNC_QUERY_STRING);

        return set;
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        if (!key.startsWith(__ASYNC_PREFIX))
            super.setAttribute(key, value);

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
                _query = (String)value;
                break;
            default:
                super.setAttribute(key, value);
        }
    }

    @Override
    public void clearAttributes()
    {
        _requestURI = null;
        _contextPath = null;
        _servletPath = null;
        _pathInfo = null;
        _query = null;
        super.clearAttributes();
    }
}
