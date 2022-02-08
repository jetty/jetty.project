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

package org.eclipse.jetty.server;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.servlet.http.PushBuilder;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class PushBuilderImpl implements PushBuilder
{
    private static final Logger LOG = LoggerFactory.getLogger(PushBuilderImpl.class);

    private static final HttpField JETTY_PUSH = new HttpField("x-http2-push", "PushBuilder");
    private static EnumSet<HttpMethod> UNSAFE_METHODS = EnumSet.of(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.DELETE,
        HttpMethod.CONNECT,
        HttpMethod.OPTIONS,
        HttpMethod.TRACE);

    private final Request _request;
    private final HttpFields.Mutable _fields;
    private String _method;
    private String _queryString;
    private String _sessionId;
    private String _path;
    private String _lastModified;

    public PushBuilderImpl(Request request, HttpFields fields, String method, String queryString, String sessionId)
    {
        super();
        _request = request;
        _fields = HttpFields.build(fields);
        _method = method;
        _queryString = queryString;
        _sessionId = sessionId;
        _fields.add(JETTY_PUSH);
        if (LOG.isDebugEnabled())
            LOG.debug("PushBuilder({} {}?{} s={})", _method, _request.getRequestURI(), _queryString, _sessionId);
    }

    @Override
    public String getMethod()
    {
        return _method;
    }

    @Override
    public PushBuilder method(String method)
    {
        Objects.requireNonNull(method);
        
        if (StringUtil.isBlank(method) || UNSAFE_METHODS.contains(HttpMethod.fromString(method)))
            throw new IllegalArgumentException("Method not allowed for push: " + method);
        _method = method;
        return this;
    }

    @Override
    public String getQueryString()
    {
        return _queryString;
    }

    @Override
    public PushBuilder queryString(String queryString)
    {
        _queryString = queryString;
        return this;
    }

    @Override
    public String getSessionId()
    {
        return _sessionId;
    }

    @Override
    public PushBuilder sessionId(String sessionId)
    {
        _sessionId = sessionId;
        return this;
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return _fields.getFieldNamesCollection();
    }

    @Override
    public String getHeader(String name)
    {
        return _fields.get(name);
    }

    @Override
    public PushBuilder setHeader(String name, String value)
    {
        _fields.put(name, value);
        return this;
    }

    @Override
    public PushBuilder addHeader(String name, String value)
    {
        _fields.add(name, value);
        return this;
    }

    @Override
    public PushBuilder removeHeader(String name)
    {
        _fields.remove(name);
        return this;
    }

    @Override
    public String getPath()
    {
        return _path;
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
        if (_path == null || _path.length() == 0)
            throw new IllegalStateException("Bad Path " + _path);

        String path = _path;
        String query = _queryString;
        int q = path.indexOf('?');
        if (q >= 0)
        {
            query = (query != null && query.length() > 0) ? (path.substring(q + 1) + '&' + query) : path.substring(q + 1);
            path = path.substring(0, q);
        }

        if (!path.startsWith("/"))
            path = URIUtil.addPaths(_request.getContextPath(), path);

        String param = null;
        if (_sessionId != null)
        {
            if (_request.isRequestedSessionIdFromURL())
                param = "jsessionid=" + _sessionId;
            // TODO else
            //      _rawFields.add("Cookie","JSESSIONID="+_sessionId);
        }

        HttpURI uri = HttpURI.build(_request.getHttpURI(), path, param, query).normalize();
        MetaData.Request push = new MetaData.Request(_method, uri, _request.getHttpVersion(), _fields);

        if (LOG.isDebugEnabled())
            LOG.debug("Push {} {} inm={} ims={}", _method, uri, _fields.get(HttpHeader.IF_NONE_MATCH), _fields.get(HttpHeader.IF_MODIFIED_SINCE));

        _request.getHttpChannel().getHttpTransport().push(push);
        _path = null;
        _lastModified = null;
    }
}
