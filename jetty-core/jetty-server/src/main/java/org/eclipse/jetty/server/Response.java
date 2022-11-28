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
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ErrorProcessor;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An asynchronous HTTP response.
 * TODO Javadoc
 */
public interface Response extends Content.Sink
{
    // This is needed so that response methods can access the wrapped Request#getContext method
    Request getRequest();

    int getStatus();

    void setStatus(int code);

    HttpFields.Mutable getHeaders();

    Supplier<HttpFields> getTrailersSupplier();

    void setTrailersSupplier(Supplier<HttpFields> trailers);

    boolean isCommitted();

    boolean isCompletedSuccessfully();

    void reset();

    CompletableFuture<Void> writeInterim(int status, HttpFields headers);

    /**
     * <p>Return a representation of the response as a chunk handler suitable for being
     * passed to the {@link Content#copy(Content.Source, Content.Sink, BiPredicate, Callback)}
     * method.  Specifically, as a chunk handler that will handle {@link Trailers} chunks
     * by adding the chunk fields to the {@link HttpFields} supplied by
     * {@link Response#getTrailersSupplier()}.</p>
     * <p>This is specifically useful for writing chunks that have been received via
     * the {@link Content.Source#read()} API, for example when echoing a request to a response:</p>
     * <pre>
     *   Content.copy(request, response, Response.asTrailerChunkHandler(response), callback);
     * </pre>
     * @param response The response for which to process trailer chunks.
     * @return A chunk handler that will add trailer chunks to the response's trailer supplied fields.
     * @see Content#copy(Content.Source, Content.Sink, BiPredicate, Callback)
     * @see Trailers
     */
    static BiPredicate<Content.Chunk, Callback> asTrailerChunkHandler(Response response)
    {
        Supplier<HttpFields> supplier = response.getTrailersSupplier();
        if (supplier == null)
            return (chunk, callback) -> false;

        return (chunk, callback) ->
        {
            if (chunk instanceof Trailers trailers)
            {
                HttpFields requestTrailers = trailers.getTrailers();
                if (requestTrailers != null)
                {
                    // Call supplier in lambda to get latest responseTrailers
                    HttpFields responseTrailers = supplier.get();
                    if (responseTrailers instanceof HttpFields.Mutable mutable)
                    {
                        mutable.add(requestTrailers);
                        callback.succeeded();
                        return true;
                    }
                }
            }
            return false;
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response.Wrapper> T as(Response response, Class<T> type)
    {
        while (response instanceof Response.Wrapper wrapper)
        {
            if (type.isInstance(wrapper))
                return (T)wrapper;
            response = wrapper.getWrapped();
        }
        return null;
    }

    static void sendRedirect(Request request, Response response, Callback callback, String location)
    {
        sendRedirect(request, response, callback, HttpStatus.MOVED_TEMPORARILY_302, location, false);
    }

    static void sendRedirect(Request request, Response response, Callback callback, int code, String location, boolean consumeAvailable)
    {
        if (!HttpStatus.isRedirection(code))
            throw new IllegalArgumentException("Not a 3xx redirect code");

        if (location == null)
            throw new IllegalArgumentException("No location");

        if (response.isCommitted())
            throw new IllegalStateException("Committed");

        // TODO: can we remove this?
        if (consumeAvailable)
        {
            while (true)
            {
                Content.Chunk chunk = response.getRequest().read();
                if (chunk == null)
                    break; // TODO really? shouldn't we just asynchronously wait?
                chunk.release();
                if (chunk.isLast())
                    break;
            }
        }

        response.getHeaders().put(HttpHeader.LOCATION, Request.toRedirectURI(request, location));
        response.setStatus(code);
        response.write(true, null, callback);
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
                if (field instanceof HttpCookie.SetCookieHttpField)
                {
                    if (!HttpCookie.match(((HttpCookie.SetCookieHttpField)field).getHttpCookie(), cookie.getName(), cookie.getDomain(), cookie.getPath()))
                        continue;
                }
                else
                {
                    if (!HttpCookie.match(field.getValue(), cookie.getName(), cookie.getDomain(), cookie.getPath()))
                        continue;
                }

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
        // Retrieve the Logger instance here, rather than having a
        // public field that will force a transitive dependency on SLF4J.
        Logger logger = LoggerFactory.getLogger(Response.class);

        // Let's be less verbose with BadMessageExceptions & QuietExceptions
        if (logger.isDebugEnabled())
            logger.debug("writeError: status={}, message={}, response={}", status, message, response, cause);
        else if (cause instanceof BadMessageException || cause instanceof QuietException)
            logger.debug("writeError: status={}, message={}, response={} {}", status, message, response, cause.toString());
        else if (cause != null)
            logger.warn("writeError: status={}, message={}, response={}", status, message, response, cause);

        if (response.isCommitted())
        {
            callback.failed(cause == null ? new IllegalStateException(message == null ? "Committed" : message) : cause);
            return;
        }

        Response.ensureConsumeAvailableOrNotPersistent(request, response);

        if (status <= 0)
            status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        if (message == null)
            message = HttpStatus.getMessage(status);

        response.setStatus(status);

        // TODO: detect recursion when an ErrorProcessor calls this method, otherwise StackOverflowError.
        Context context = request.getContext();
        Request.Processor errorProcessor = context.getErrorProcessor();
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
        response.write(true, null, callback);
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
        if (originalResponse instanceof HttpChannelState.ChannelResponse channelResponse)
            return channelResponse.getContentBytesWritten();
        return -1;
    }

    static void ensureConsumeAvailableOrNotPersistent(Request request, Response response)
    {
        switch (request.getConnectionMetaData().getHttpVersion())
        {
            case HTTP_1_0:
                if (consumeAvailable(request))
                    return;

                // Remove any keep-alive value in Connection headers
                response.getHeaders().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return null;
                    String v = fields.stream()
                        .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                        .collect(Collectors.joining(", "));
                    if (StringUtil.isEmpty(v))
                        return null;

                    return new HttpField(HttpHeader.CONNECTION, v);
                });
                break;

            case HTTP_1_1:
                if (consumeAvailable(request))
                    return;

                // Add close value to Connection headers
                response.getHeaders().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return HttpFields.CONNECTION_CLOSE;

                    if (fields.stream().anyMatch(f -> f.contains(HttpHeaderValue.CLOSE.asString())))
                    {
                        if (fields.size() == 1)
                        {
                            HttpField f = fields.get(0);
                            if (HttpFields.CONNECTION_CLOSE.equals(f))
                                return f;
                        }

                        return new HttpField(HttpHeader.CONNECTION, fields.stream()
                            .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                            .collect(Collectors.joining(", ")));
                    }

                    return new HttpField(HttpHeader.CONNECTION,
                        Stream.concat(fields.stream()
                                    .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s))),
                                Stream.of(HttpHeaderValue.CLOSE.asString()))
                            .collect(Collectors.joining(", ")));
                });
                break;

            default:
                break;
        }
    }

    static boolean consumeAvailable(Request request)
    {
        while (true)
        {
            Content.Chunk chunk = request.read();
            if (chunk == null)
                return false;
            chunk.release();
            if (chunk.isLast())
                return true;
        }
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
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return getWrapped().getTrailersSupplier();
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
            getWrapped().setTrailersSupplier(trailers);
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            getWrapped().write(last, byteBuffer, callback);
        }

        @Override
        public boolean isCommitted()
        {
            return getWrapped().isCommitted();
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return getWrapped().isCompletedSuccessfully();
        }

        @Override
        public void reset()
        {
            getWrapped().reset();
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            return getWrapped().writeInterim(status, headers);
        }
    }
}
