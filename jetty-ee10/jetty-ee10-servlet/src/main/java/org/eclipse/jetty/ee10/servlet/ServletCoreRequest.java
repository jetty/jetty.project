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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;

import static org.eclipse.jetty.util.URIUtil.addEncodedPaths;
import static org.eclipse.jetty.util.URIUtil.encodePath;

/**
 * Wraps a {@link jakarta.servlet.ServletRequest} as a core {@link Request}.
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
        _httpFields = new HttpServletRequestHttpFields(request, _servletContextRequest.getHeaders());

        String includedServletPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
        boolean included = includedServletPath != null;

        HttpURI.Mutable builder = HttpURI.build();
        builder.scheme(request.getScheme())
            .authority(request.getServerName(), request.getServerPort());

        if (included)
            builder.path(addEncodedPaths(request.getContextPath(), encodePath(ResourceServlet.getIncludedPathInContext(request, includedServletPath))));
        else if (request.getDispatcherType() != DispatcherType.REQUEST)
            builder.path(addEncodedPaths(request.getContextPath(), encodePath(URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo()))));
        else
            builder.path(request.getRequestURI());
        builder.query(request.getQueryString());
        _uri = builder.asImmutable();

        _source = _wrapped ? null : _servletContextRequest;
    }

    private Content.Source source() throws IOException
    {
        if (_source == null)
            _source = _wrapped ? Content.Source.from(getServletRequest().getInputStream()) : _servletContextRequest;
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
            // Deplete the wrapping request's ServletInputStream using only non-blocking API, then
            // eventually delegate to consumeAvailable() to make the response non-persistent if needed.
            ByteBufferPool byteBufferPool = _servletContextRequest.getComponents().getByteBufferPool();
            RetainableByteBuffer rbb = byteBufferPool.acquire(IO.DEFAULT_BUFFER_SIZE, false);
            try
            {
                ServletInputStream sis = getServletRequest().getInputStream();
                byte[] array = rbb.getByteBuffer().array();
                while (sis.isReady() && !sis.isFinished())
                {
                    int read = sis.read(array);
                    if (read == -1)
                        break;
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored exception while depleting wrapped ServletInputStream", x);
            }
            finally
            {
                rbb.release();
            }
        }
        return _servletContextRequest.consumeAvailable();
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
        return _servletContextRequest.getTunnelSupport();
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
                {
                    set.add(e.nextElement());
                }
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

    private static final class HttpServletRequestHttpFields implements HttpFields
    {
        private final HttpServletRequest _httpServletRequest;
        private final List<HttpField> _fields;
        private final HttpFields _baseHttpFields;

        private HttpServletRequestHttpFields(HttpServletRequest httpServletRequest, HttpFields httpFields)
        {
            _httpServletRequest = httpServletRequest;
            _baseHttpFields = httpFields;
            _fields = new ArrayList<>();
            Enumeration<String> headerNames = _httpServletRequest.getHeaderNames();
            while (headerNames.hasMoreElements())
            {
                String name = headerNames.nextElement();
                Enumeration<String> values = _httpServletRequest.getHeaders(name);
                while (values.hasMoreElements())
                {
                    String value = values.nextElement();
                    _fields.add(new HttpField(name, value));
                }
            }
        }

        @Override
        public QuotedQualityCSV newQuotedQualityCSV(ToIntFunction<String> secondaryOrdering)
        {
            return new QuotedQualityCSV(_baseHttpFields, secondaryOrdering);
        }

        @Override
        public QuotedCSV newQuotedCSV(boolean keepQuotes)
        {
            return new QuotedCSV(_baseHttpFields, keepQuotes);
        }

        @Override
        public HttpField getField(String name)
        {
            String value = _httpServletRequest.getHeader(name);
            if (value == null)
                return null;
            // If getHeader() was overridden without also getHeaderNames() then _fields may not have the correct header value.
            return new HttpField(name, value);
        }

        @Override
        public HttpField getField(HttpHeader header)
        {
            String name = header.asString();
            String value = _httpServletRequest.getHeader(header.asString());
            if (value == null)
                return null;
            // If getHeader() was overridden without also getHeaderNames() then _fields may not have the correct header value.
            return new HttpField(name, value);
        }

        @Override
        public String get(String name)
        {
            return _httpServletRequest.getHeader(name);
        }

        @Override
        public String getLast(HttpHeader header)
        {
            return HttpFields.super.getLast(header);
        }

        @Override
        public String get(HttpHeader header)
        {
            return _httpServletRequest.getHeader(header.asString());
        }

        @Override
        public ListIterator<HttpField> listIterator(int index)
        {
            return Collections.unmodifiableList(_fields).listIterator(index);
        }
    }
}
