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

import java.util.Set;

import jakarta.servlet.http.PushBuilder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.URIUtil;

class PushBuilderImpl implements PushBuilder
{
    private final ServletContextRequest _request;
    private final HttpFields.Mutable _headers;
    private String _method;
    private String _query;
    private String _sessionId;
    private String _path;

    public PushBuilderImpl(ServletContextRequest request, HttpFields.Mutable headers, String sessionId)
    {
        _request = request;
        _headers = headers;
        _method = request.getMethod();
        _query = request.getHttpURI().getQuery();
        _sessionId = sessionId;
    }

    @Override
    public PushBuilder method(String method)
    {
        HttpMethod httpMethod = HttpMethod.fromString(method);
        if (httpMethod == null || !httpMethod.isSafe())
            throw new IllegalArgumentException("method not allowed for push: " + method);
        _method = httpMethod.asString();
        return this;
    }

    @Override
    public PushBuilder queryString(String queryString)
    {
        _query = queryString;
        return this;
    }

    @Override
    public PushBuilder sessionId(String sessionId)
    {
        _sessionId = sessionId;
        return this;
    }

    @Override
    public PushBuilder setHeader(String name, String value)
    {
        _headers.put(name, value);
        return this;
    }

    @Override
    public PushBuilder addHeader(String name, String value)
    {
        _headers.add(name, value);
        return this;
    }

    @Override
    public PushBuilder removeHeader(String name)
    {
        _headers.remove(name);
        return this;
    }

    @Override
    public PushBuilder path(String path)
    {
        _path = path;
        return this;
    }

    @Override
    public void push()
    {
        String pushPath = getPath();
        if (pushPath == null || pushPath.isBlank())
            throw new IllegalArgumentException("invalid push path: " + pushPath);

        String query = getQueryString();
        String pushQuery = query;
        int q = pushPath.indexOf('?');
        if (q > 0)
        {
            pushQuery = pushPath.substring(q + 1);
            if (query != null)
                pushQuery += "&" + query;
            pushPath = pushPath.substring(0, q);
        }

        if (!pushPath.startsWith("/"))
            pushPath = URIUtil.addPaths(_request.getContext().getContextPath(), pushPath);

        String pushParam = null;
        if (_sessionId != null)
        {
            if (_request.getServletApiRequest().isRequestedSessionIdFromURL())
                pushParam = "jsessionid=" + _sessionId;
        }

        HttpURI pushURI = HttpURI.build(_request.getHttpURI(), pushPath, pushParam, pushQuery).normalize();
        MetaData.Request push = new MetaData.Request(_method, pushURI, _request.getConnectionMetaData().getHttpVersion(), _headers);
        _request.push(push);

        _path = null;
    }

    @Override
    public String getMethod()
    {
        return _method;
    }

    @Override
    public String getQueryString()
    {
        return _query;
    }

    @Override
    public String getSessionId()
    {
        return _sessionId;
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return _headers.getFieldNamesCollection();
    }

    @Override
    public String getHeader(String name)
    {
        return _headers.get(name);
    }

    @Override
    public String getPath()
    {
        return _path;
    }
}
