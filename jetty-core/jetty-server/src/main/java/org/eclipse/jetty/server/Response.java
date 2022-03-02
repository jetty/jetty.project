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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.ErrorProcessor;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An asynchronous HTTP response.
 * TODO Javadoc
 */
public interface Response
{
    Logger LOG = LoggerFactory.getLogger(ContextRequest.class);

    int getStatus();

    void setStatus(int code);

    HttpFields.Mutable getHeaders();

    HttpFields.Mutable getTrailers();

    void write(boolean last, Callback callback, ByteBuffer... content);

    default void write(boolean last, Callback callback, String utf8Content)
    {
        write(last, callback, StandardCharsets.UTF_8.encode(utf8Content));
    }

    void push(MetaData.Request request);

    boolean isCommitted();

    void reset();

    // TODO: inline and remove
    default void addHeader(String name, String value)
    {
        getHeaders().add(name, value);
    }

    // TODO: inline and remove
    default void addHeader(HttpHeader header, String value)
    {
        getHeaders().add(header, value);
    }

    // TODO: inline and remove
    default void setHeader(String name, String value)
    {
        getHeaders().put(name, value);
    }

    // TODO: inline and remove
    default void setHeader(HttpHeader header, String value)
    {
        getHeaders().put(header, value);
    }

    // TODO: inline and remove
    default void setContentType(String mimeType)
    {
        getHeaders().put(HttpHeader.CONTENT_TYPE, mimeType);
    }

    // TODO: inline and remove
    default void setContentLength(long length)
    {
        getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, length);
    }

    static void writeError(Request request, Response response, Callback callback, Throwable cause)
    {
        if (cause == null)
            cause = new Throwable("unknown cause");
        int status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        String message = cause.toString();
        if (cause instanceof BadMessageException bad)
        {
            status = bad.getCode();
            message = bad.getReason();
        }
        writeError(request, response, callback, status, message, cause);
    }

    static void writeError(Response response, Request request, int status, Callback callback)
    {
        writeError(request, response, callback, status, null, null);
    }

    static void writeError(Request request, Response response, Callback callback, int status, String message)
    {
        writeError(request, response, callback, status, message, null);
    }

    static void writeError(Request request, Response response, Callback callback, int status, String message, Throwable cause)
    {
        // Let's be less verbose with BadMessageExceptions & QuietExceptions
        if (!LOG.isDebugEnabled() && (cause instanceof BadMessageException || cause instanceof QuietException))
            LOG.warn("{} {}", message, cause.getMessage());
        else
            LOG.warn("{} {}", message, response, cause);

        if (response.isCommitted())
        {
            callback.failed(cause == null ? new IllegalStateException(message == null ? "Committed" : message) : cause);
            return;
        }

        if (status <= 0)
            status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        if (message == null)
            message = HttpStatus.getMessage(status);

        response.setStatus(status);

        Context context = request.getContext();
        Request.Processor errorProcessor = context.getErrorProcessor();
        if (errorProcessor == null)
            errorProcessor = request.getHttpChannel().getServer().getErrorProcessor();

        if (errorProcessor != null)
        {
            Request errorRequest = new ErrorProcessor.ErrorRequest(request, status, message, cause);
            try
            {
                errorProcessor.process(errorRequest, response, callback);
                return;
            }
            catch (Exception e)
            {
                if (cause != null && cause != e)
                    cause.addSuppressed(e);
            }
        }

        // fall back to very empty error page
        response.getHeaders().put(ErrorProcessor.ERROR_CACHE_CONTROL);
        response.write(true, callback);
    }

    class Wrapper implements Response
    {
        private final Response _wrapped;

        public Wrapper(Response wrapped)
        {
            _wrapped = wrapped;
        }

        public Response getWrapped()
        {
            return _wrapped;
        }
        
        @Override
        public int getStatus()
        {
            return getWrapped().getStatus();
        }

        @Override
        public void setStatus(int code)
        {
            getWrapped().setStatus(code);
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return getWrapped().getHeaders();
        }

        @Override
        public HttpFields.Mutable getTrailers()
        {
            return getWrapped().getTrailers();
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            getWrapped().write(last, callback, content);
        }

        @Override
        public void push(MetaData.Request request)
        {
            getWrapped().push(request);
        }

        @Override
        public boolean isCommitted()
        {
            return getWrapped().isCommitted();
        }

        @Override
        public void reset()
        {
            getWrapped().reset();
        }
    }
}
