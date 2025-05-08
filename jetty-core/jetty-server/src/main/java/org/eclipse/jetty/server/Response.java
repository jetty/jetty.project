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

package org.eclipse.jetty.server;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.jetty.http.ComplianceViolationException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The representation of an HTTP response, for any protocol version (HTTP/1.1, HTTP/2, HTTP/3).</p>
 */
public interface Response extends Content.Sink
{
    /**
     * @return the {@link Request} associated with this {@code Response}
     */
    Request getRequest();

    /**
     * @return the response HTTP status code
     */
    int getStatus();

    /**
     * Set the response HTTP status code.
     * @param code the response HTTP status code
     */
    void setStatus(int code);

    /**
     * @return the response HTTP headers
     */
    HttpFields.Mutable getHeaders();

    /**
     * @return a supplier for the HTTP trailers
     */
    Supplier<HttpFields> getTrailersSupplier();

    /**
     * <p>Sets the supplier for the HTTP trailers.</p>
     * <p>The method {@link Supplier#get()} may be called by the
     * implementation multiple times, so it is important that
     * the same value is returned in every invocation.</p>
     * <p>Example:</p>
     * <pre>{@code
     * // Correct usage.
     * HttpFields.Mutable trailers = HttpFields.build();
     * response.setTrailersSupplier(() -> trailers);
     *
     * // WRONG usage, as the value changes for
     * // every invocation of supplier.get().
     * response.setTrailersSupplier(() -> HttpFields.build());
     * }</pre>
     *
     * @param trailers a supplier for the HTTP trailers
     */
    void setTrailersSupplier(Supplier<HttpFields> trailers);

    /**
     * <p>Returns whether this response has already been committed.</p>
     * <p>Committing a response means that the HTTP status code and HTTP headers
     * cannot be modified anymore, typically because they have already been
     * serialized and sent over the network.</p>
     *
     * @return whether this response has already been committed
     */
    boolean isCommitted();

    /**
     * <p>Returns whether the last write has been initiated on the response.</p>
     *
     * @return {@code true} if {@code last==true} has been passed to {@link #write(boolean, ByteBuffer, Callback)}.
     */
    boolean hasLastWrite();

    /**
     * <p>Returns whether the response completed successfully.</p>
     * <p>The response HTTP status code, HTTP headers and content
     * have been successfully serialized and sent over the network
     * without errors.</p>
     *
     * @return whether the response completed successfully
     */
    boolean isCompletedSuccessfully();

    /**
     * <p>Resets this response, clearing the HTTP status code, HTTP headers
     * and HTTP trailers.</p>
     *
     * @throws IllegalStateException if the response is already
     * {@link #isCommitted() committed}
     */
    void reset();

    /**
     * <p>Writes an {@link HttpStatus#isInterim(int) HTTP interim response},
     * with the given HTTP status code and HTTP headers.</p>
     * <p>It is possible to write more than one interim response, for example
     * in case of {@link HttpStatus#EARLY_HINTS_103}.</p>
     * <p>The returned {@link CompletableFuture} is notified of the result
     * of this write, whether it succeeded or failed.</p>
     *
     * @param status the interim HTTP status code
     * @param headers the HTTP headers
     * @return a {@link CompletableFuture} with the result of the write
     */
    CompletableFuture<Void> writeInterim(int status, HttpFields headers);

    /**
     * {@inheritDoc}
     * <p>The invocation of the passed {@code Callback} is serialized
     * with previous calls of this method, so that it is not invoked until
     * any invocation of the callback of a previous call to this method
     * has returned.</p>
     * <p>Thus a {@code Callback} should not block waiting for a callback
     * of a future call to this method.</p>
     *
     * @param last whether the ByteBuffer is the last to write
     * @param byteBuffer the ByteBuffer to write
     * @param callback the callback to notify when the write operation is complete
     */
    @Override
    void write(boolean last, ByteBuffer byteBuffer, Callback callback);

    /**
     * <p>Returns a chunk processor suitable to be passed to the
     * {@link Content#copy(Content.Source, Content.Sink, Content.Chunk.Processor, Callback)}
     * method, that will handle {@link Trailers} chunks
     * by adding their fields to the {@link HttpFields} supplied by
     * {@link Response#getTrailersSupplier()}.</p>
     * <p>This is specifically useful for writing trailers that have been received via
     * the {@link Content.Source#read()} API, for example when echoing a request to a response:</p>
     * <pre>
     *   Content.copy(request, response, Response.asTrailerChunkHandler(response), callback);
     * </pre>
     * @param response The response for which to process a trailers chunk.
     *                 If the {@link Response#setTrailersSupplier(Supplier)}
     *                 method has not been called prior to this method, then a noop processor is returned.
     * @return A chunk processor that will add trailer chunks to the response's trailer supplied fields.
     * @see Content#copy(Content.Source, Content.Sink, Content.Chunk.Processor, Callback)
     * @see Trailers
     */
    static Content.Chunk.Processor newTrailersChunkProcessor(Response response)
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

    /**
     * Unwrap a Response back to the given type, ensuring that we do not cross a
     * context boundary (as might be the case during cross-context dispatch).
     *
     * @param response the possibly wrapped response to unwrap
     * @param type the type to unwrap to
     * @return the response unwrapped back to the given type, or null if it cannot be
     * unwrapped to that type or a context boundary is crossed.
     */
    static <T extends Response> T asInContext(Response response, Class<T> type)
    {
        //the context whose boundary should not be crossed
        Context context = response == null ? null : (response.getRequest() == null ? null : response.getRequest().getContext());

        if (context == null)
            return Response.as(response, type);

        while (response != null)
        {
            if (response.getRequest() == null)
                return null;
            if (response.getRequest().getContext() != context)
                return null;
            if (type.isInstance(response))
                return (T)response;
            response = response instanceof Response.Wrapper wrapper ? wrapper.getWrapped() : null;
        }
        return null;
    }

    /**
     * <p>Unwraps the given response, recursively, until the wrapped instance
     * is an instance of the given type, otherwise returns {@code null}.</p>
     *
     * @param response the response to unwrap
     * @param type the response type to find
     * @return the response as the given type, or {@code null}
     * @param <T> the response type
     * @see Wrapper
     */
    @SuppressWarnings("unchecked")
    static <T> T as(Response response, Class<T> type)
    {
        while (response != null)
        {
            if (type.isInstance(response))
                return (T)response;
            response = response instanceof Response.Wrapper wrapper ? wrapper.getWrapped() : null;
        }
        return null;
    }

    /**
     * <p>Sends a HTTP redirect status code to the given location,
     * without consuming the available request content. The {@link HttpStatus#SEE_OTHER_303}
     * code is used, unless the request is HTTP/1.0, in which case {@link HttpStatus#MOVED_TEMPORARILY_302} is used,
     * unless the request is not a {@code GET} and the protocol is {@code HTTP/1.1} or later, in which case a
     * {@link HttpStatus#SEE_OTHER_303} is used to make the client consistently redirect with a {@code GET}.
     * </p>
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param location the redirect location as an absolute URI or encoded relative URI path.
     * @see #sendRedirect(Request, Response, Callback, int, String, boolean)
     */
    static void sendRedirect(Request request, Response response, Callback callback, String location)
    {
        sendRedirect(request, response, callback, location, false);
    }

    /**
     * <p>Sends HTTP redirect status code to the given location,
     * without consuming the available request content. The {@link HttpStatus#SEE_OTHER_303}
     * code is used, unless the request is HTTP/1.0, in which case {@link HttpStatus#MOVED_TEMPORARILY_302} is used,
     * unless the request is not a {@code GET} and the protocol is {@code HTTP/1.1} or later, in which case a
     * {@link HttpStatus#SEE_OTHER_303} is used to make the client consistently redirect with a {@code GET}.
     * </p>
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param location the redirect location as an absolute URI or encoded relative URI path.
     * @param consumeAvailable whether to consumer the available request content
     * @see #sendRedirect(Request, Response, Callback, int, String, boolean)
     */
    static void sendRedirect(Request request, Response response, Callback callback, String location, boolean consumeAvailable)
    {
        int code = HttpMethod.GET.is(request.getMethod()) || request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
            ? HttpStatus.MOVED_TEMPORARILY_302
            : HttpStatus.SEE_OTHER_303;
        sendRedirect(request, response, callback, code, location, consumeAvailable);
    }

    /**
     * <p>Sends a {@code 302} HTTP redirect status code to the given location.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param code the redirect HTTP status code
     * @param location the redirect location as an absolute URI or encoded relative URI path.
     * @param consumeAvailable whether to consumer the available request content
     * @see #toRedirectURI(Request, String)
     * @throws IllegalArgumentException if the status code is not a redirect, or the location is {@code null}
     * @throws IllegalStateException if the response is already {@link #isCommitted() committed}
     */
    static void sendRedirect(Request request, Response response, Callback callback, int code, String location, boolean consumeAvailable)
    {
        if (!HttpStatus.isRedirection(code))
        {
            callback.failed(new IllegalArgumentException("Not a 3xx redirect code"));
            return;
        }

        if (location == null)
        {
            callback.failed(new IllegalArgumentException("No location"));
            return;
        }

        if (response.isCommitted())
        {
            callback.failed(new IllegalStateException("Committed"));
            return;
        }

        try
        {
            if (consumeAvailable)
            {
                while (true)
                {
                    Content.Chunk chunk = response.getRequest().read();
                    if (chunk == null)
                    {
                        response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                        break;
                    }
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }
            }

            response.getHeaders().put(HttpHeader.LOCATION, toRedirectURI(request, location));
            response.setStatus(code);
            response.write(true, null, callback);
        }
        catch (Throwable failure)
        {
            callback.failed(failure);
        }
    }

    /**
     * Common point to generate a proper "Location" header for redirects.
     *
     * @param request the request the redirect should be based on (needed when relative locations are provided, so that
     * server name, scheme, port can be built out properly)
     * @param location the redirect location as an absolute URI or encoded relative URI path. If a relative path starts
     *                 with '/', then it is relative to the root, otherwise it is relative to the request.
     * @return the full redirect "Location" URL (including scheme, host, port, path, etc...)
     */
    static String toRedirectURI(Request request, String location)
    {
        HttpConfiguration httpConfiguration = request.getConnectionMetaData().getHttpConfiguration();

        // is the URI absolute already?
        if (!URIUtil.hasScheme(location))
        {
            // The location is relative
            HttpURI uri = request.getHttpURI();

            // Is it relative to the request?
            if (!location.startsWith("/"))
            {
                String path = uri.getPath();
                String parent = (path.endsWith("/")) ? path : URIUtil.parentPath(path);
                location = URIUtil.addEncodedPaths(parent, location);
            }

            // Normalize out any dot dot segments
            location = URIUtil.normalizePathQuery(location);
            if (location == null)
                throw new IllegalStateException("redirect path cannot be above root");

            // if relative redirects are not allowed?
            if (!httpConfiguration.isRelativeRedirectAllowed())
                // make the location an absolute URI
                location = URIUtil.newURI(uri.getScheme(), Request.getServerName(request), Request.getServerPort(request), location, null);
        }

        UriCompliance redirectCompliance = httpConfiguration.getRedirectUriCompliance();
        if (redirectCompliance != null)
        {
            String violations = UriCompliance.checkUriCompliance(redirectCompliance, HttpURI.from(location), null);
            if (StringUtil.isNotBlank(violations))
                throw new IllegalArgumentException(violations);
        }

        return location;
    }

    /**
     * <p>Adds an HTTP {@link HttpHeader#SET_COOKIE} header to the response.</p>
     *
     * @param response the HTTP response
     * @param cookie the HTTP cookie to add
     * @see #putCookie(Response, HttpCookie)
     */
    static void addCookie(Response response, HttpCookie cookie)
    {
        if (StringUtil.isBlank(cookie.getName()))
            throw new IllegalArgumentException("Cookie.name cannot be blank/null");

        Request request = response.getRequest();
        CookieCompliance compliance = request.getConnectionMetaData().getHttpConfiguration().getResponseCookieCompliance();
        try
        {
            response.getHeaders().add(new HttpCookieUtils.SetCookieHttpField(HttpCookieUtils.checkSameSite(cookie, request.getContext()), compliance));
        }
        catch (ComplianceViolationException e)
        {
            HttpChannel.from(request).getComplianceViolationListener().onComplianceViolation(e.getEvent());
            throw e;
        }

        // Expire responses with set-cookie headers, so they do not get cached.
        if (!response.getHeaders().contains(HttpHeader.EXPIRES))
            response.getHeaders().add(HttpFields.EXPIRES_01JAN1970);
    }

    /**
     * <p>Put a HTTP {@link HttpHeader#SET_COOKIE} header to the response.</p>
     * <p>If a matching {@link HttpHeader#SET_COOKIE} already exists for matching name, path, domain etc.
     * then it will be replaced.</p>
     *
     * @param response the HTTP response
     * @param cookie the HTTP cookie to add
     * @see #addCookie(Response, HttpCookie)
     */
    static void putCookie(Response response, HttpCookie cookie)
    {
        if (StringUtil.isBlank(cookie.getName()))
            throw new IllegalArgumentException("Cookie.name cannot be blank/null");

        Request request = response.getRequest();
        HttpConfiguration httpConfiguration = request.getConnectionMetaData().getHttpConfiguration();
        CookieCompliance compliance = httpConfiguration.getResponseCookieCompliance();

        HttpField setCookie;
        try
        {
            setCookie = new HttpCookieUtils.SetCookieHttpField(HttpCookieUtils.checkSameSite(cookie, request.getContext()), compliance);
        }
        catch (ComplianceViolationException e)
        {
            HttpChannel.from(request).getComplianceViolationListener().onComplianceViolation(e.getEvent());
            throw e;
        }

        boolean expires = false;

        for (ListIterator<HttpField> i = response.getHeaders().listIterator(); i.hasNext(); )
        {
            HttpField field = i.next();
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;

            switch (header)
            {
                case SET_COOKIE ->
                {
                    if (field instanceof HttpCookieUtils.SetCookieHttpField setCookieHttpField)
                    {
                        if (!HttpCookieUtils.match(setCookieHttpField.getHttpCookie(), cookie.getName(), cookie.getDomain(), cookie.getPath()))
                            continue;
                    }
                    else
                    {
                        if (!HttpCookieUtils.match(field.getValue(), cookie.getName(), cookie.getDomain(), cookie.getPath()))
                            continue;
                    }

                    if (setCookie == null)
                    {
                        i.remove();
                    }
                    else
                    {
                        i.set(setCookie);
                        setCookie = null;
                    }
                }

                case EXPIRES -> expires = true;
            }
        }

        if (setCookie != null)
            response.getHeaders().add(setCookie);

        // Expire responses with set-cookie headers, so they do not get cached.
        if (!expires)
            response.getHeaders().add(HttpFields.EXPIRES_01JAN1970);
    }

    /**
     * Replace a cookie
     * @param response the HTTP response
     * @param cookie the HTTP cookie to add
     * @deprecated use {@link #putCookie(Response, HttpCookie)}
     */
    @Deprecated
    static void replaceCookie(Response response, HttpCookie cookie)
    {
        putCookie(response, cookie);
    }

    /**
     * <p>Writes an error response with HTTP status code {@code 500}.</p>
     * <p>The error {@link Request.Handler} returned by {@link Context#getErrorHandler()},
     * if any, is invoked.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param cause the cause of the error
     */
    static void writeError(Request request, Response response, Callback callback, Throwable cause)
    {
        if (cause == null)
            cause = new Throwable("unknown cause");
        int status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        String message = cause.toString();
        if (cause instanceof HttpException httpException)
        {
            status = httpException.getCode();
            message = httpException.getReason();
        }
        writeError(request, response, callback, status, message, cause);
    }

    /**
     * <p>Writes an error response with the given HTTP status code.</p>
     * <p>The error {@link Request.Handler} returned by {@link Context#getErrorHandler()},
     * if any, is invoked.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param status the error HTTP status code
     */
    static void writeError(Request request, Response response, Callback callback, int status)
    {
        writeError(request, response, callback, status, null, null);
    }

    /**
     * <p>Writes an error response with the given HTTP status code,
     * and the given message in the response content.</p>
     * <p>The error {@link Request.Handler} returned by {@link Context#getErrorHandler()},
     * if any, is invoked.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param status the error HTTP status code
     * @param message the error message to write in the response content
     */
    static void writeError(Request request, Response response, Callback callback, int status, String message)
    {
        writeError(request, response, callback, status, message, null);
    }

    /**
     * <p>Writes an error response with the given HTTP status code,
     * and the given message in the response content.</p>
     * <p>The error {@link Request.Handler} returned by {@link Context#getErrorHandler()},
     * if any, is invoked.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param callback the callback to complete
     * @param status the error HTTP status code
     * @param message the error message to write in the response content
     * @param cause the cause of the error
     */
    static void writeError(Request request, Response response, Callback callback, int status, String message, Throwable cause)
    {
        // Retrieve the Logger instance here, rather than having a
        // public field that will force a transitive dependency on SLF4J.
        Logger logger = LoggerFactory.getLogger(Response.class);

        // Let's be less verbose with BadMessageExceptions & QuietExceptions
        if (logger.isDebugEnabled())
            logger.debug("writeError: status={}, message={}, response={}", status, message, response, cause);
        else if (cause instanceof QuietException || cause instanceof TimeoutException)
            logger.debug("writeError: status={}, message={}, response={} {}", status, message, response, cause.toString());
        else if (cause != null)
            logger.warn("writeError: status={}, message={}, response={}", status, message, response, cause);

        if (response.isCommitted())
        {
            callback.failed(cause == null ? new IllegalStateException(message == null ? "Committed" : message) : cause);
            return;
        }

        ResponseUtils.ensureConsumeAvailableOrNotPersistent(request, response);

        if (status <= 0)
            status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        if (message == null)
            message = HttpStatus.getMessage(status);

        response.setStatus(status);

        // TODO: detect recursion when an ErrorHandler calls this method, otherwise StackOverflowError.
        Context context = request.getContext();
        Request.Handler errorHandler = context.getErrorHandler();
        if (errorHandler != null)
        {
            Request errorRequest = new ErrorHandler.ErrorRequest(request, status, message, cause);
            try
            {
                if (errorHandler.handle(errorRequest, response, callback))
                    return;
            }
            catch (Throwable e)
            {
                if (cause != null && cause != e)
                    cause.addSuppressed(e);
            }
        }

        // fall back to very empty error page
        response.getHeaders().put(ErrorHandler.ERROR_CACHE_CONTROL);
        response.write(true, null, callback);
    }

    /**
     * <p>Unwraps the given response until the innermost wrapped response instance.</p>
     *
     * @param response the response to unwrap
     * @return the innermost wrapped response instance
     * @see Wrapper
     */
    static Response getOriginalResponse(Response response)
    {
        while (response instanceof Response.Wrapper wrapped)
        {
            response = wrapped.getWrapped();
        }
        return response;
    }

    /**
     * @param response the HTTP response
     * @return the number of response content bytes written to the network so far,
     * or {@code -1} if the number is unknown
     */
    static long getContentBytesWritten(Response response)
    {
        Response originalResponse = getOriginalResponse(response);
        if (originalResponse instanceof HttpChannelState.ChannelResponse channelResponse)
            return channelResponse.getContentBytesWritten();
        return -1;
    }

    /**
     * <p>Wraps a {@link Response} as a {@link OutputStream} that performs buffering. The necessary
     * {@link ByteBufferPool} is taken from the request's connector while the size and direction of the buffer
     * is read from the request's {@link HttpConfiguration}.</p>
     * <p>This is equivalent to:</p>
     * <p>{@code Content.Sink.asOutputStream(Response.asBufferedSink(request, response))}</p>
     * @param request the request from which to get the buffering sink's settings
     * @param response the response to wrap
     * @return a buffering {@link OutputStream}
     */
    static OutputStream asBufferedOutputStream(Request request, Response response)
    {
        return Content.Sink.asOutputStream(Response.asBufferedSink(request, response));
    }

    /**
     * Wraps a {@link Response} as a {@link Content.Sink} that performs buffering. The necessary
     * {@link ByteBufferPool} is taken from the request's connector while the size, direction of the buffer
     * and commit size are read from the request's {@link HttpConfiguration}.
     * @param request the request from which to get the buffering sink's settings
     * @param response the response to wrap
     * @return a buffering {@link Content.Sink}
     */
    static Content.Sink asBufferedSink(Request request, Response response)
    {
        ConnectionMetaData connectionMetaData = request.getConnectionMetaData();
        ByteBufferPool bufferPool = connectionMetaData.getConnector().getByteBufferPool();
        HttpConfiguration httpConfiguration = connectionMetaData.getHttpConfiguration();
        int bufferSize = httpConfiguration.getOutputBufferSize();
        boolean useOutputDirectByteBuffers = httpConfiguration.isUseOutputDirectByteBuffers();
        int outputAggregationSize = httpConfiguration.getOutputAggregationSize();
        return Content.Sink.asBuffered(response, bufferPool, useOutputDirectByteBuffers, outputAggregationSize, bufferSize);
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

        public Response getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Request getRequest()
        {
            return _request;
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
        public boolean isCommitted()
        {
            return getWrapped().isCommitted();
        }

        @Override
        public boolean hasLastWrite()
        {
            return getWrapped().hasLastWrite();
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

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            getWrapped().write(last, byteBuffer, callback);
        }
    }
}
