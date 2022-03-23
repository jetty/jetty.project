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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ListIterator;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.ErrorProcessor;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An asynchronous HTTP response.
 * TODO Javadoc
 */
public interface Response extends Content.Writer
{
    Logger LOG = LoggerFactory.getLogger(ContextRequest.class);

    // This is needed so that response methods can access the wrapped Request#getContext method
    Request getRequest();

    int getStatus();

    void setStatus(int code);

    HttpFields.Mutable getHeaders();

    // TODO: change this to trailers(Supplier<HttpFields> supplier)
    //  so that the method name is less confusing?
    //  (it has a side effect, but looks like a normal getter).
    HttpFields.Mutable getTrailers();

    @Override
    void write(boolean last, Callback callback, ByteBuffer... content);

    default void write(boolean last, Callback callback, String utf8Content)
    {
        write(last, callback, StandardCharsets.UTF_8.encode(utf8Content));
    }

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

    /*
     * Blocking write utility
     */
    static void write(Response response, boolean last, ByteBuffer... content) throws IOException
    {
        try (Blocking.Callback callback = Blocking.callback())
        {
            response.write(last, callback, content);
            callback.block();
        }
    }

    static void sendRedirect(Request request, Response response, Callback callback, String location)
    {
        sendRedirect(request, response, callback, HttpStatus.MOVED_TEMPORARILY_302, location, false);
    }

    static void sendRedirect(Request request, Response response, Callback callback, int code, String location, boolean consumeAll)
    {
        if (!HttpStatus.isRedirection(code))
            throw new IllegalArgumentException("Not a 3xx redirect code");

        if (location == null)
            throw new IllegalArgumentException("No location");

        if (response.isCommitted())
            throw new IllegalStateException("Committed");

        // TODO: can we remove this?
        if (consumeAll)
        {
            while (true)
            {
                Content content = response.getRequest().readContent();
                if (content == null)
                    break; // TODO really? shouldn't we just asynchronously wait?
                content.release();
                if (content.isLast())
                    break;
            }
        }

        response.getHeaders().put(HttpHeader.LOCATION, Request.toRedirectURI(request, location));
        response.setStatus(code);
        response.write(true, callback);
    }

    static void addCookie(Response response, HttpCookie cookie)
    {
        if (StringUtil.isBlank(cookie.getName()))
            throw new IllegalArgumentException("Cookie.name cannot be blank/null");

        Request request = response.getRequest();
        response.getHeaders().add(new HttpCookie.SetCookieHttpField(HttpCookie.checkSameSite(cookie, request.getContext()),
            request.getConnectionMetaData().getHttpConfiguration().getResponseCookieCompliance()));

        // Expire responses with set-cookie headers so they do not get cached.
        response.getHeaders().put(HttpFields.EXPIRES_01JAN1970);
    }

    static void replaceCookie(Response response, HttpCookie cookie)
    {
        if (StringUtil.isBlank(cookie.getName()))
            throw new IllegalArgumentException("Cookie.name cannot be blank/null");

        Request request = response.getRequest();
        HttpConfiguration httpConfiguration = request.getConnectionMetaData().getHttpConfiguration();

        for (ListIterator<HttpField> i = response.getHeaders().listIterator(); i.hasNext(); )
        {
            HttpField field = i.next();

            if (field.getHeader() == HttpHeader.SET_COOKIE)
            {
                CookieCompliance compliance = httpConfiguration.getResponseCookieCompliance();
                HttpCookie oldCookie;
                if (field instanceof HttpCookie.SetCookieHttpField)
                    oldCookie = ((HttpCookie.SetCookieHttpField)field).getHttpCookie();
                else
                    oldCookie = new HttpCookie(field.getValue());

                if (!cookie.getName().equals(oldCookie.getName()))
                    continue;

                if (cookie.getDomain() == null)
                {
                    if (oldCookie.getDomain() != null)
                        continue;
                }
                else if (!cookie.getDomain().equalsIgnoreCase(oldCookie.getDomain()))
                    continue;

                if (cookie.getPath() == null)
                {
                    if (oldCookie.getPath() != null)
                        continue;
                }
                else if (!cookie.getPath().equals(oldCookie.getPath()))
                    continue;

                i.set(new HttpCookie.SetCookieHttpField(HttpCookie.checkSameSite(cookie, request.getContext()), compliance));
                return;
            }
        }

        // Not replaced, so add normally
        addCookie(response, cookie);
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

    static void writeError(Request request, Response response, Callback callback, int status)
    {
        writeError(request, response, callback, status, null, null);
    }

    static void writeError(Request request, Response response, Callback callback, int status, String message)
    {
        writeError(request, response, callback, status, message, null);
    }

    static void writeError(Request request, Response response, Callback callback, int status, String message, Throwable cause)
    {
        // TODO what about 102 Processing?

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
            errorProcessor = request.getConnectionMetaData().getConnector().getServer().getErrorProcessor();

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

    static Response getOriginalResponse(Response response)
    {
        while (response instanceof Response.Wrapper wrapped)
        {
            response = wrapped.getWrapped();
        }
        return response;
    }

    static long getContentBytesWritten(Response response)
    {
        Response originalResponse = getOriginalResponse(response);
        if (originalResponse instanceof HttpChannel.ChannelResponse channelResponse)
            return channelResponse.getContentBytesWritten();
        return -1;
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
        public Request getRequest()
        {
            return _request;
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

    // TODO test and document
    static OutputStream asOutputStream(Response response)
    {
        return Content.asOutputStream(response);
    }
}
