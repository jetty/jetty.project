//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * An asynchronous HTTP response.
 * TODO Javadoc
 */
public interface Response
{
    Request getRequest();

    int getStatus();

    void setStatus(int code);

    HttpFields.Mutable getHeaders();

    HttpFields.Mutable getTrailers();

    void write(boolean last, Callback callback, ByteBuffer... content);

    default void write(boolean last, Callback callback, String utf8Content)
    {
        write(last, callback, BufferUtil.toBuffer(utf8Content, StandardCharsets.UTF_8));
    }

    void push(MetaData.Request request);

    boolean isCommitted();

    void reset();

    default Response getWrapped()
    {
        return null;
    }

    Response getWrapper();

    void setWrapper(Response response);

    default void addHeader(String name, String value)
    {
        getHeaders().add(name, value);
    }

    default void addHeader(HttpHeader header, String value)
    {
        getHeaders().add(header, value);
    }

    default void setHeader(String name, String value)
    {
        getHeaders().put(name, value);
    }

    default void setHeader(HttpHeader header, String value)
    {
        getHeaders().put(header, value);
    }

    default void setContentType(String mimeType)
    {
        getHeaders().put(HttpHeader.CONTENT_TYPE, mimeType);
    }

    default void setContentLength(long length)
    {
        getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, length);
    }

    default void writeError(int status, String message, Callback callback)
    {
        if (isCommitted())
        {
            callback.failed(new IllegalStateException("Committed"));
            return;
        }

        setStatus(status);
        ByteBuffer content = BufferUtil.EMPTY_BUFFER;
        HttpField errorCacheControl = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store"); // TODO static
        getHeaders().put(errorCacheControl);
        if (!HttpStatus.hasNoBody(status))
        {
            // TODO generalize the error handling
            List<String> acceptable = getRequest().getHeaders().getQualityCSV(HttpHeader.ACCEPT, QuotedQualityCSV.MOST_SPECIFIC_MIME_ORDERING);
            List<String> charset = getRequest().getHeaders().getQualityCSV(HttpHeader.ACCEPT_CHARSET);
            getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_8859_1.asString());
            if (message == null)
                message = HttpStatus.getMessage(status);
            content = BufferUtil.toBuffer("<h1>Bad Message " + status + "</h1><pre>reason: " + message + "</pre>");
        }

        write(true, callback, content);
    }

    class Wrapper implements Response
    {
        private final Response _wrapped;

        public Wrapper(Response wrapped)
        {
            _wrapped = wrapped;
            _wrapped.setWrapper(this);
        }

        @Override
        public int getStatus()
        {
            return _wrapped.getStatus();
        }

        @Override
        public void setStatus(int code)
        {
            _wrapped.setStatus(code);
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _wrapped.getHeaders();
        }

        @Override
        public HttpFields.Mutable getTrailers()
        {
            return _wrapped.getTrailers();
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            _wrapped.write(last, callback, content);
        }

        @Override
        public void push(MetaData.Request request)
        {
            _wrapped.push(request);
        }

        @Override
        public boolean isCommitted()
        {
            return _wrapped.isCommitted();
        }

        @Override
        public void reset()
        {
            _wrapped.reset();
        }

        @Override
        public Response getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Request getRequest()
        {
            return _wrapped.getRequest();
        }

        @Override
        public Response getWrapper()
        {
            return _wrapped.getWrapper();
        }

        @Override
        public void setWrapper(Response response)
        {
            _wrapped.setWrapper(response);
        }
    }
}
