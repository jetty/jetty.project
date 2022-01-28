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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * An asynchronous HTTP response.
 * TODO Javadoc
 */
public interface Response
{
    Request getRequest();

    Callback getCallback();

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

    default void writeError(Throwable cause, Callback callback)
    {
        if (cause == null)
            cause = new Throwable("unknown cause");
        int status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        String message = cause.toString();
        if (cause instanceof BadMessageException)
        {
            BadMessageException bad = (BadMessageException)cause;
            status = bad.getCode();
            message = bad.getReason();
        }
        writeError(status, message, cause, callback);
    }

    default void writeError(int status, Callback callback)
    {
        writeError(status, null, null, callback);
    }

    default void writeError(int status, String message, Callback callback)
    {
        writeError(status, message, null, callback);
    }

    default void writeError(int status, String message, Throwable cause, Callback callback)
    {
        if (isCommitted())
        {
            callback.failed(new IllegalStateException("Committed"));
            return;
        }

        if (status <= 0)
            status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        if (message == null)
            message = HttpStatus.getMessage(status);

        setStatus(status);

        ContextHandler.Context context = getRequest().get(ContextRequest.class, ContextRequest::getContext);
        Handler errorHandler = ErrorHandler.getErrorHandler(getRequest().getChannel().getServer(), context == null ? null : context.getContextHandler());

        if (errorHandler != null)
        {
            Request errorRequest = new ErrorHandler.ErrorRequest(getRequest(), this, status, message, cause, callback);
            try
            {
                errorHandler.handle(errorRequest);
                if (errorRequest.isAccepted())
                    return;
            }
            catch (Exception e)
            {
                if (cause != null && cause != e)
                    cause.addSuppressed(e);
            }
        }

        // fall back to very empty error page
        getHeaders().put(ErrorHandler.ERROR_CACHE_CONTROL);
        write(true, callback);
    }

    class Wrapper implements Response
    {
        private final Request _request;
        private final Response _wrapped;

        public Wrapper(Request request, Response wrapped)
        {
            _request = request;
            _wrapped = wrapped;
        }

        @Override
        public Callback getCallback()
        {
            return _wrapped.getCallback();
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
            return _request;
        }
    }
}
