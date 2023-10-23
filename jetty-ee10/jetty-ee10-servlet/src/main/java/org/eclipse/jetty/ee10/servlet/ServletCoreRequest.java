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

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.URIUtil;

import static org.eclipse.jetty.util.URIUtil.addEncodedPaths;
import static org.eclipse.jetty.util.URIUtil.encodePath;

/**
 * Wrap a {@link jakarta.servlet.ServletRequest} as a core {@link Request}.
 * <p>
 * Whilst similar to a {@link Request.Wrapper}, this class is not a {@code Wrapper}
 * as callers should not be able to access {@link Wrapper#getWrapped()} and bypass
 * the {@link jakarta.servlet.ServletRequest}.
 * </p>
 * <p>
 * The current implementation does not support any read operations.
 * </p>
 */
class ServletCoreRequest implements Request
{
    private final HttpServletRequest _servletRequest;
    private final ServletContextRequest _servletContextRequest;
    private final HttpFields _httpFields;
    private final HttpURI _uri;

    ServletCoreRequest(HttpServletRequest request)
    {
        _servletRequest = request;
        _servletContextRequest = ServletContextRequest.getServletContextRequest(_servletRequest);

        HttpFields.Mutable fields = HttpFields.build();

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements())
        {
            String headerName = headerNames.nextElement();
            Enumeration<String> headerValues = request.getHeaders(headerName);
            while (headerValues.hasMoreElements())
            {
                String headerValue = headerValues.nextElement();
                fields.add(new HttpField(headerName, headerValue));
            }
        }

        _httpFields = fields.asImmutable();
        String includedServletPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
        boolean included = includedServletPath != null;

        HttpURI.Mutable builder = HttpURI.build();
        builder.scheme(request.getScheme())
            .authority(request.getServerName(), request.getServerPort());

        if (included)
            builder.path(addEncodedPaths(request.getContextPath(), encodePath(DefaultServlet.getIncludedPathInContext(request, includedServletPath, false))));
        else if (request.getDispatcherType() != DispatcherType.REQUEST)
            builder.path(addEncodedPaths(request.getContextPath(), encodePath(URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo()))));
        else
            builder.path(request.getRequestURI());
        builder.query(request.getQueryString());
        _uri = builder.asImmutable();
    }

    @Override
    public HttpFields getHeaders()
    {
        return _httpFields;
    }

    @Override
    public HttpURI getHttpURI()
    {
        return _uri;
    }

    @Override
    public String getId()
    {
        return _servletRequest.getRequestId();
    }

    @Override
    public String getMethod()
    {
        return _servletRequest.getMethod();
    }

    public HttpServletRequest getServletRequest()
    {
        return _servletRequest;
    }

    @Override
    public boolean isSecure()
    {
        return _servletRequest.isSecure();
    }

    @Override
    public Object removeAttribute(String name)
    {
        Object value = _servletRequest.getAttribute(name);
        _servletRequest.removeAttribute(name);
        return value;
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        Object value = _servletRequest.getAttribute(name);
        _servletRequest.setAttribute(name, attribute);
        return value;
    }

    @Override
    public Object getAttribute(String name)
    {
        return _servletRequest.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        Set<String> set = new HashSet<>();
        Enumeration<String> e = _servletRequest.getAttributeNames();
        while (e.hasMoreElements())
        {
            set.add(e.nextElement());
        }
        return set;
    }

    @Override
    public void clearAttributes()
    {
        Enumeration<String> e = _servletRequest.getAttributeNames();
        while (e.hasMoreElements())
        {
            _servletRequest.removeAttribute(e.nextElement());
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Components getComponents()
    {
        return _servletContextRequest.getComponents();
    }

    @Override
    public ConnectionMetaData getConnectionMetaData()
    {
        return _servletContextRequest.getConnectionMetaData();
    }

    @Override
    public Context getContext()
    {
        return _servletContextRequest.getContext();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpFields getTrailers()
    {
        return _servletContextRequest.getTrailers();
    }

    @Override
    public long getBeginNanoTime()
    {
        return _servletContextRequest.getBeginNanoTime();
    }

    @Override
    public long getHeadersNanoTime()
    {
        return _servletContextRequest.getHeadersNanoTime();
    }

    @Override
    public Content.Chunk read()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean consumeAvailable()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
    {
        _servletContextRequest.addIdleTimeoutListener(onIdleTimeout);
    }

    @Override
    public void addFailureListener(Consumer<Throwable> onFailure)
    {
        _servletContextRequest.addFailureListener(onFailure);
    }

    @Override
    public TunnelSupport getTunnelSupport()
    {
        return null;
    }

    @Override
    public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
    {
        _servletContextRequest.addHttpStreamWrapper(wrapper);
    }

    @Override
    public Session getSession(boolean create)
    {
        return Session.getSession(_servletRequest.getSession(create));
    }
}
