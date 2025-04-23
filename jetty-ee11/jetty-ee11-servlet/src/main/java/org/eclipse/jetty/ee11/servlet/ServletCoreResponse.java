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

package org.eclipse.jetty.ee11.servlet;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.io.ByteBufferInputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

/**
 * A {@link HttpServletResponse} wrapped as a core {@link Response}.
 * All write operations are internally converted to blocking writes on the servlet API.
 */
public class ServletCoreResponse implements Response
{
    public Response wrap(Request coreRequest, HttpServletResponse httpServletResponse, boolean included)
    {
        return new ServletCoreResponse(coreRequest, httpServletResponse, included);
    }

    private final HttpServletResponse _httpServletResponse;
    private final Request _coreRequest;
    private final HttpFields.Mutable _httpFields;
    private final boolean _included;
    private final ServletContextResponse _servletContextResponse;
    private final boolean _wrapped;

    ServletCoreResponse(Request coreRequest, HttpServletResponse httpServletResponse, boolean included)
    {
        _coreRequest = coreRequest;
        _httpServletResponse = httpServletResponse;
        _servletContextResponse = ServletContextResponse.getServletContextResponse(httpServletResponse);
        _wrapped = !(httpServletResponse instanceof ServletApiResponse);
        HttpFields.Mutable fields = new HttpServletResponseHttpFields(httpServletResponse);
        if (included)
        {
            // If included, accept but ignore mutations.
            fields = new HttpFields.Mutable.Wrapper(fields)
            {
                @Override
                public HttpField onAddField(HttpField field)
                {
                    String name = field == null ? null : field.getName();
                    if (!StringUtil.isBlank(name) && name.startsWith(Dispatcher.JETTY_INCLUDE_HEADER_PREFIX))
                    {
                        return new HttpField(name.substring(Dispatcher.JETTY_INCLUDE_HEADER_PREFIX.length()), field.getValue());
                    }

                    return null;
                }

                @Override
                public boolean onRemoveField(HttpField field)
                {
                    return false;
                }

                @Override
                public HttpField onReplaceField(HttpField oldField, HttpField newField)
                {
                    return oldField;
                }
            };
        }
        _httpFields = fields;
        _included = included;
    }

    @Override
    public HttpFields.Mutable getHeaders()
    {
        return _httpFields;
    }

    public HttpServletResponse getServletResponse()
    {
        return _httpServletResponse;
    }

    @Override
    public boolean hasLastWrite()
    {
        return _servletContextResponse.hasLastWrite();
    }

    @Override
    public boolean isCompletedSuccessfully()
    {
        return _servletContextResponse.isCompletedSuccessfully();
    }

    @Override
    public boolean isCommitted()
    {
        return _httpServletResponse.isCommitted();
    }

    private boolean isWriting()
    {
        return _servletContextResponse.isWriting();
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        if (_included)
            last = false;
        try
        {
            if (!_wrapped && !_servletContextResponse.isWritingOrStreaming())
            {
                // We can bypass the HttpOutput stream, but we need to update its bytes written
                _servletContextResponse.getHttpOutput().addBytesWritten(byteBuffer.remaining());
                _servletContextResponse.write(last, byteBuffer, callback);
            }
            else
            {
                if (BufferUtil.hasContent(byteBuffer))
                {
                    if (isWriting())
                    {
                        String characterEncoding = _httpServletResponse.getCharacterEncoding();
                        try (ByteBufferInputStream bbis = new ByteBufferInputStream(byteBuffer);
                             InputStreamReader reader = new InputStreamReader(bbis, characterEncoding))
                        {
                            IO.copy(reader, _httpServletResponse.getWriter());
                        }

                        if (last)
                            _httpServletResponse.getWriter().close();
                    }
                    else
                    {
                        BufferUtil.writeTo(byteBuffer, _httpServletResponse.getOutputStream());
                        if (last)
                            _httpServletResponse.getOutputStream().close();
                    }
                }

                callback.succeeded();
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    @Override
    public Request getRequest()
    {
        return _coreRequest;
    }

    @Override
    public int getStatus()
    {
        return _httpServletResponse.getStatus();
    }

    @Override
    public void setStatus(int code)
    {
        if (_included)
            return;
        _httpServletResponse.setStatus(code);
    }

    @Override
    public Supplier<HttpFields> getTrailersSupplier()
    {
        return null;
    }

    @Override
    public void setTrailersSupplier(Supplier<HttpFields> trailers)
    {
    }

    @Override
    public void reset()
    {
        _httpServletResponse.reset();
    }

    @Override
    public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
    {
        return _servletContextResponse.writeInterim(status, headers);
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), this._coreRequest, _httpServletResponse);
    }

    private static class HttpServletResponseHttpFields implements HttpFields.Mutable
    {
        private final HttpServletResponse _response;

        private HttpServletResponseHttpFields(HttpServletResponse response)
        {
            _response = response;
        }

        @Override
        public ListIterator<HttpField> listIterator(int index)
        {
            // The minimum requirement is to implement the listIterator, but it is inefficient.
            // Other methods are implemented for efficiency.
            final ListIterator<HttpField> list = _response.getHeaderNames().stream()
                .map(n -> new HttpField(n, _response.getHeader(n)))
                .collect(Collectors.toList())
                .listIterator(index);

            return new ListIterator<>()
            {
                HttpField _last;

                @Override
                public boolean hasNext()
                {
                    return list.hasNext();
                }

                @Override
                public HttpField next()
                {
                    return _last = list.next();
                }

                @Override
                public boolean hasPrevious()
                {
                    return list.hasPrevious();
                }

                @Override
                public HttpField previous()
                {
                    return _last = list.previous();
                }

                @Override
                public int nextIndex()
                {
                    return list.nextIndex();
                }

                @Override
                public int previousIndex()
                {
                    return list.previousIndex();
                }

                @Override
                public void remove()
                {
                    if (_last != null)
                    {
                        // This is not exactly the right semantic for repeated field names
                        list.remove();
                        _response.setHeader(_last.getName(), null);
                    }
                }

                @Override
                public void set(HttpField httpField)
                {
                    list.set(httpField);
                    _response.setHeader(httpField.getName(), httpField.getValue());
                }

                @Override
                public void add(HttpField httpField)
                {
                    list.add(httpField);
                    _response.addHeader(httpField.getName(), httpField.getValue());
                }
            };
        }

        @Override
        public Mutable add(String name, String value)
        {
            _response.addHeader(name, value);
            return this;
        }

        @Override
        public Mutable add(HttpHeader header, HttpHeaderValue value)
        {
            _response.addHeader(header.asString(), value.asString());
            return this;
        }

        @Override
        public Mutable add(HttpHeader header, String value)
        {
            _response.addHeader(header.asString(), value);
            return this;
        }

        @Override
        public Mutable add(HttpField field)
        {
            _response.addHeader(field.getName(), field.getValue());
            return this;
        }

        @Override
        public Mutable put(HttpField field)
        {
            _response.setHeader(field.getName(), field.getValue());
            return this;
        }

        @Override
        public Mutable put(String name, String value)
        {
            _response.setHeader(name, value);
            return this;
        }

        @Override
        public Mutable put(HttpHeader header, HttpHeaderValue value)
        {
            _response.setHeader(header.asString(), value.asString());
            return this;
        }

        @Override
        public Mutable put(HttpHeader header, String value)
        {
            _response.setHeader(header.asString(), value);
            return this;
        }

        @Override
        public Mutable put(String name, List<String> list)
        {
            Objects.requireNonNull(name);
            Objects.requireNonNull(list);
            boolean first = true;
            for (String s : list)
            {
                if (first)
                    _response.setHeader(name, s);
                else
                    _response.addHeader(name, s);
                first = false;
            }
            return this;
        }

        @Override
        public Mutable remove(HttpHeader header)
        {
            _response.setHeader(header.asString(), null);
            return this;
        }

        @Override
        public Mutable remove(EnumSet<HttpHeader> fields)
        {
            for (HttpHeader header : fields)
                remove(header);
            return this;
        }

        @Override
        public Mutable remove(String name)
        {
            _response.setHeader(name, null);
            return this;
        }
    }
}
