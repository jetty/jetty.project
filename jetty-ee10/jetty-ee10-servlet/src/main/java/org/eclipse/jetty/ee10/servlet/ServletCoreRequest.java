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

import static org.eclipse.jetty.util.URIUtil.addEncodedPaths;
import static org.eclipse.jetty.util.URIUtil.encodePath;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.URIUtil;

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
public class ServletCoreRequest implements Request
{
    public static Request wrap(HttpServletRequest httpServletRequest)
    {
        return new ServletCoreRequest(httpServletRequest, null);
    }

    private final HttpServletRequest _servletRequest;
    private final ServletContextRequest _servletContextRequest;
    private final HttpFields _httpFields;
    private final HttpURI _uri;
    private final Attributes _attributes;
    private final boolean _wrapped;
    private Content.Source _source;

    ServletCoreRequest(HttpServletRequest request, Attributes attributes)
    {
        _servletRequest = request;
        _wrapped = !(request instanceof ServletApiRequest);
        _servletContextRequest = ServletContextRequest.getServletContextRequest(_servletRequest);
        _attributes = attributes == null ? _servletContextRequest : attributes;

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
        builder.scheme(request.getScheme()).authority(request.getServerName(), request.getServerPort());

        if (included)
            builder.path(addEncodedPaths(
                request.getContextPath(),
                encodePath(DefaultServlet.getIncludedPathInContext(request, includedServletPath, false))));
        else if (request.getDispatcherType() != DispatcherType.REQUEST)
            builder.path(addEncodedPaths(
                request.getContextPath(),
                encodePath(URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo()))));
        else
            builder.path(request.getRequestURI());
        builder.query(request.getQueryString());
        _uri = builder.asImmutable();

        _source = _wrapped ? null : _servletContextRequest;
    }

    private Content.Source source() throws IOException
    {
        if (_source == null)
            _source = _wrapped ? new InputStreamContentSource(getServletRequest().getInputStream()) : _servletContextRequest;
        return _source;
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
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _attributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    @Override
    public void fail(Throwable failure)
    {
        try
        {
            source().fail(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(failure, t);
        }
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
        try
        {
            source().demand(demandCallback);
        }
        catch (Throwable t)
        {
            demandCallback.run();
        }
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
        try
        {
            return source().read();
        }
        catch (Throwable t)
        {
            return Content.Chunk.from(t, true);
        }
    }

    @Override
    public boolean consumeAvailable()
    {
        if (_wrapped)
        {
            try
            {
                Content.Source.consumeAll(source());
                return true;
            }
            catch (IOException e)
            {
                return false;
            }
        }
        else
        {
            return _servletContextRequest.consumeAvailable();
        }
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

    public static class ServletAttributes implements Attributes
    {
        private final HttpServletRequest _servletRequest;
        private Set<String> _attributeNames;

        public ServletAttributes(HttpServletRequest httpServletRequest)
        {
            _servletRequest = httpServletRequest;
        }

        @Override
        public Object removeAttribute(String name)
        {
            Object value = _servletRequest.getAttribute(name);
            if (value != null)
                _attributeNames = null;
            _servletRequest.removeAttribute(name);
            return value;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            Object value = _servletRequest.getAttribute(name);
            if (value == null)
                _attributeNames = null;
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
            Set<String> set = _attributeNames;
            if (set == null)
            {
                set = new HashSet<>();
                Enumeration<String> e = _servletRequest.getAttributeNames();
                while (e.hasMoreElements())
                    set.add(e.nextElement());
                _attributeNames = set;
            }
            return set;
        }

        @Override
        public void clearAttributes()
        {
            Enumeration<String> e = _servletRequest.getAttributeNames();
            _attributeNames = null;
            while (e.hasMoreElements())
            {
                _servletRequest.removeAttribute(e.nextElement());
            }
        }
    }
}
