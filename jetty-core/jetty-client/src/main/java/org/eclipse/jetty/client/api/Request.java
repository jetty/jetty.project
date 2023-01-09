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

package org.eclipse.jetty.client.api;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Fields;

/**
 * <p>{@link Request} represents an HTTP request, and offers a fluent interface to customize
 * various attributes such as the path, the headers, the content, etc.</p>
 * <p>You can create {@link Request} objects via {@link HttpClient#newRequest(String)} and
 * you can send them using either {@link #send()} for a blocking semantic, or
 * {@link #send(Response.CompleteListener)} for an asynchronous semantic.</p>
 *
 * @see Response
 */
public interface Request
{
    /**
     * @return the URI scheme of this request, such as "http" or "https"
     */
    String getScheme();

    /**
     * @param scheme the URI scheme of this request, such as "http" or "https"
     * @return this request object
     */
    Request scheme(String scheme);

    /**
     * @return the URI host of this request, such as "127.0.0.1" or "google.com"
     */
    String getHost();

    /**
     * @param host the URI host of this request, such as "127.0.0.1" or "google.com"
     * @return this request object
     */
    default Request host(String host)
    {
        return this;
    }

    /**
     * @return the URI port of this request such as 80 or 443
     */
    int getPort();

    /**
     * @param port the URI port of this request such as 80 or 443
     * @return this request object
     */
    default Request port(int port)
    {
        return this;
    }

    /**
     * @return the method of this request, such as GET or POST, as a String
     */
    String getMethod();

    /**
     * @param method the method of this request, such as GET or POST
     * @return this request object
     */
    Request method(HttpMethod method);

    /**
     * @param method the method of this request, such as GET or POST
     * @return this request object
     */
    Request method(String method);

    /**
     * @return the URI path of this request, such as "/" or "/path" - without the query
     * @see #getQuery()
     */
    String getPath();

    /**
     * Specifies the URI path - and possibly the query - of this request.
     * If the query part is specified, parameter values must be properly
     * {@link URLEncoder#encode(String, String) UTF-8 URL encoded}.
     * For example, if the value for parameter "currency" is the euro symbol &euro; then the
     * query string for this parameter must be "currency=%E2%82%AC".
     * For transparent encoding of parameter values, use {@link #param(String, String)}.
     *
     * @param path the URI path of this request, such as "/" or "/path?param=1"
     * @return this request object
     */
    Request path(String path);

    /**
     * @return the URI query string of this request such as "param=1"
     * @see #getPath()
     * @see #getParams()
     */
    String getQuery();

    /**
     * @return the full URI of this request such as {@code https://host:port/path?param=1}
     */
    URI getURI();

    /**
     * @return the HTTP version of this request, such as "HTTP/1.1"
     */
    HttpVersion getVersion();

    /**
     * @param version the HTTP version of this request, such as "HTTP/1.1"
     * @return this request object
     */
    Request version(HttpVersion version);

    /**
     * @return the URI query parameters of this request
     */
    Fields getParams();

    /**
     * Adds a URI query parameter with the given name and value.
     * The value is {@link URLEncoder#encode(String, String) UTF-8 URL encoded}.
     *
     * @param name the name of the query parameter
     * @param value the value of the query parameter
     * @return this request object
     */
    Request param(String name, String value);

    /**
     * @return the headers of this request
     */
    HttpFields getHeaders();

    /**
     * Modifies the headers of this request.
     *
     * @param consumer the code that modifies the headers of this request
     * @return this request object
     */
    Request headers(Consumer<HttpFields.Mutable> consumer);

    /**
     * @return the supplier of the trailers of this request
     */
    Supplier<HttpFields> getTrailersSupplier();

    /**
     * Provides the trailers of this request.
     *
     * @param trailers the supplier of the trailers of this request
     * @return this request object
     */
    Request trailersSupplier(Supplier<HttpFields> trailers);

    /**
     * @return the cookies associated with this request
     */
    List<HttpCookie> getCookies();

    /**
     * @param cookie a cookie for this request
     * @return this request object
     */
    Request cookie(HttpCookie cookie);

    /**
     * <p>Tags this request with the given metadata tag.</p>
     * <p>Each different tag will create a different destination,
     * even if the destination origin is the same.</p>
     * <p>This is particularly useful in proxies, where requests
     * for the same origin but from different clients may be tagged
     * with client's metadata (e.g. the client remote address).</p>
     * <p>The tag metadata class must correctly implement
     * {@link Object#hashCode()} and {@link Object#equals(Object)}
     * so that it can be used, along with the origin, to identify
     * a destination.</p>
     *
     * @param tag the metadata to tag the request with
     * @return this request object
     */
    Request tag(Object tag);

    /**
     * @return the metadata this request has been tagged with
     */
    Object getTag();

    /**
     * @param name the name of the attribute
     * @param value the value of the attribute
     * @return this request object
     */
    Request attribute(String name, Object value);

    /**
     * @return the attributes of this request
     */
    Map<String, Object> getAttributes();

    /**
     * @return the request content of this request
     */
    Content getBody();

    /**
     * @param content the request content of this request
     * @return this request object
     */
    Request body(Content content);

    /**
     * Shortcut method to specify a file as a content for this request, with the default content type of
     * "application/octet-stream".
     *
     * @param file the file to upload
     * @return this request object
     * @throws IOException if the file does not exist or cannot be read
     */
    Request file(Path file) throws IOException;

    /**
     * Shortcut method to specify a file as a content for this request, with the given content type.
     *
     * @param file the file to upload
     * @param contentType the content type of the file
     * @return this request object
     * @throws IOException if the file does not exist or cannot be read
     */
    Request file(Path file, String contentType) throws IOException;

    /**
     * @return the user agent for this request
     */
    String getAgent();

    /**
     * @param agent the user agent for this request (corresponds to the {@code User-Agent} header)
     * @return this request object
     */
    Request agent(String agent);

    /**
     * @param accepts the media types that are acceptable in the response, such as
     * "text/plain;q=0.5" or "text/html" (corresponds to the {@code Accept} header)
     * @return this request object
     */
    Request accept(String... accepts);

    /**
     * @return the idle timeout for this request, in milliseconds
     */
    long getIdleTimeout();

    /**
     * @param timeout the idle timeout for this request
     * @param unit the idle timeout unit
     * @return this request object
     */
    Request idleTimeout(long timeout, TimeUnit unit);

    /**
     * @return the total timeout for this request, in milliseconds;
     * zero or negative if the timeout is disabled
     */
    long getTimeout();

    /**
     * @param timeout the total timeout for the request/response conversation;
     * use zero or a negative value to disable the timeout
     * @param unit the timeout unit
     * @return this request object
     */
    Request timeout(long timeout, TimeUnit unit);

    /**
     * @return whether this request follows redirects
     */
    boolean isFollowRedirects();

    /**
     * @param follow whether this request follows redirects
     * @return this request object
     */
    Request followRedirects(boolean follow);

    /**
     * @param listenerClass the class of the listener, or null for all listeners classes
     * @param <T> the type of listener class
     * @return the listeners for request events of the given class
     */
    <T extends RequestListener> List<T> getRequestListeners(Class<T> listenerClass);

    /**
     * @param listener a listener for request events
     * @return this request object
     */
    Request listener(Listener listener);

    /**
     * @param listener a listener for request queued event
     * @return this request object
     */
    Request onRequestQueued(QueuedListener listener);

    /**
     * @param listener a listener for request begin event
     * @return this request object
     */
    Request onRequestBegin(BeginListener listener);

    /**
     * @param listener a listener for request headers event
     * @return this request object
     */
    Request onRequestHeaders(HeadersListener listener);

    /**
     * @param listener a listener for request commit event
     * @return this request object
     */
    Request onRequestCommit(CommitListener listener);

    /**
     * @param listener a listener for request content events
     * @return this request object
     */
    Request onRequestContent(ContentListener listener);

    /**
     * @param listener a listener for request success event
     * @return this request object
     */
    Request onRequestSuccess(SuccessListener listener);

    /**
     * @param listener a listener for request failure event
     * @return this request object
     */
    Request onRequestFailure(FailureListener listener);

    /**
     * @param listener a listener for response begin event
     * @return this request object
     */
    Request onResponseBegin(Response.BeginListener listener);

    /**
     * @param listener a listener for response header event
     * @return this request object
     */
    Request onResponseHeader(Response.HeaderListener listener);

    /**
     * @param listener a listener for response headers event
     * @return this request object
     */
    Request onResponseHeaders(Response.HeadersListener listener);

    /**
     * @param listener a consuming listener for response content events
     * @return this request object
     */
    Request onResponseContent(Response.ContentListener listener);

    /**
     * @param listener an asynchronous listener for response content events
     * @return this request object
     */
    Request onResponseContentAsync(Response.AsyncContentListener listener);

    /**
     * @param listener a listener for response success event
     * @return this request object
     */
    Request onResponseSuccess(Response.SuccessListener listener);

    /**
     * @param listener a listener for response failure event
     * @return this request object
     */
    Request onResponseFailure(Response.FailureListener listener);

    /**
     * @param listener a listener for driving {@link org.eclipse.jetty.io.Content.Source}
     * @return this request object
     */
    Request onResponseContentSource(Response.ContentSourceListener listener);

    /**
     * <p>Sets a handler for pushed resources.</p>
     * <p>When resources are pushed from the server, the given {@code pushHandler}
     * is invoked for every pushed resource.
     * The parameters to the {@code BiFunction} are this request and the
     * synthesized request for the pushed resource.
     * The {@code BiFunction} should return a {@code CompleteListener} that
     * may also implement other listener interfaces to be notified of various
     * response events, or {@code null} to signal that the pushed resource
     * should be canceled.</p>
     *
     * @param pushHandler a handler for pushed resource events
     * @return this request object
     */
    Request onPush(BiFunction<Request, Request, Response.CompleteListener> pushHandler);

    /**
     * @param listener a listener for complete event
     * @return this request object
     */
    Request onComplete(Response.CompleteListener listener);

    /**
     * Sends this request and returns the response.
     * <p>
     * This method should be used when a simple blocking semantic is needed, and when it is known
     * that the response content can be buffered without exceeding memory constraints.
     * <p>
     * For example, this method is not appropriate to download big files from a server; consider using
     * {@link #send(Response.CompleteListener)} instead, passing your own {@link Response.Listener} or a utility
     * listener such as {@link InputStreamResponseListener}.
     * <p>
     * The method returns when the {@link Response.CompleteListener complete event} is fired.
     *
     * @return a {@link ContentResponse} for this request
     * @throws InterruptedException if send thread is interrupted
     * @throws TimeoutException if send times out
     * @throws ExecutionException if execution fails
     * @see Response.CompleteListener#onComplete(Result)
     */
    ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException;

    /**
     * <p>Sends this request and asynchronously notifies the given listener for response events.</p>
     * <p>This method should be used when the application needs to be notified of the various response events
     * as they happen, or when the application needs to efficiently manage the response content.</p>
     * <p>The listener passed to this method may implement not only {@link Response.CompleteListener}
     * but also other response listener interfaces, and all the events implemented will be notified.
     * This allows application code to write a single listener class to handle all relevant events.</p>
     *
     * @param listener the listener that receives response events
     */
    void send(Response.CompleteListener listener);

    /**
     * Attempts to abort the send of this request.
     *
     * @param cause the abort cause, must not be null
     * @return whether the abort succeeded
     */
    CompletableFuture<Boolean> abort(Throwable cause);

    /**
     * @return the abort cause passed to {@link #abort(Throwable)},
     * or null if this request has not been aborted
     */
    Throwable getAbortCause();

    /**
     * Common, empty, super-interface for request listeners.
     */
    public interface RequestListener extends EventListener
    {
    }

    /**
     * Listener for the request queued event.
     */
    public interface QueuedListener extends RequestListener
    {
        /**
         * Callback method invoked when the request is queued, waiting to be sent
         *
         * @param request the request being queued
         */
        public void onQueued(Request request);
    }

    /**
     * Listener for the request begin event.
     */
    public interface BeginListener extends RequestListener
    {
        /**
         * Callback method invoked when the request begins being processed in order to be sent.
         * This is the last opportunity to modify the request.
         *
         * @param request the request that begins being processed
         */
        public void onBegin(Request request);
    }

    /**
     * Listener for the request headers event.
     */
    public interface HeadersListener extends RequestListener
    {
        /**
         * Callback method invoked when the request headers (and perhaps small content) are ready to be sent.
         * The request has been converted into bytes, but not yet sent to the server, and further modifications
         * to the request may have no effect.
         *
         * @param request the request that is about to be committed
         */
        public void onHeaders(Request request);
    }

    /**
     * Listener for the request committed event.
     */
    public interface CommitListener extends RequestListener
    {
        /**
         * Callback method invoked when the request headers (and perhaps small content) have been sent.
         * The request is now committed, and in transit to the server, and further modifications to the
         * request may have no effect.
         *
         * @param request the request that has been committed
         */
        public void onCommit(Request request);
    }

    /**
     * Listener for the request content event.
     */
    public interface ContentListener extends RequestListener
    {
        /**
         * Callback method invoked when a chunk of request content has been sent successfully.
         * Changes to bytes in the given buffer have no effect, as the content has already been sent.
         *
         * @param request the request that has been committed
         * @param content the content
         */
        public void onContent(Request request, ByteBuffer content);
    }

    /**
     * Listener for the request succeeded event.
     */
    public interface SuccessListener extends RequestListener
    {
        /**
         * Callback method invoked when the request has been successfully sent.
         *
         * @param request the request sent
         */
        public void onSuccess(Request request);
    }

    /**
     * Listener for the request failed event.
     */
    public interface FailureListener extends RequestListener
    {
        /**
         * Callback method invoked when the request has failed to be sent
         *
         * @param request the request that failed
         * @param failure the failure
         */
        public void onFailure(Request request, Throwable failure);
    }

    /**
     * Listener for all request events.
     */
    public interface Listener extends QueuedListener, BeginListener, HeadersListener, CommitListener, ContentListener, SuccessListener, FailureListener
    {
        @Override
        public default void onQueued(Request request)
        {
        }

        @Override
        public default void onBegin(Request request)
        {
        }

        @Override
        public default void onHeaders(Request request)
        {
        }

        @Override
        public default void onCommit(Request request)
        {
        }

        @Override
        public default void onContent(Request request, ByteBuffer content)
        {
        }

        @Override
        public default void onSuccess(Request request)
        {
        }

        @Override
        public default void onFailure(Request request, Throwable failure)
        {
        }

        /**
         * An empty implementation of {@link Listener}
         */
        public static class Adapter implements Listener
        {
        }
    }

    /**
     * <p>A client-side representation of HTTP request content.</p>
     */
    public interface Content extends org.eclipse.jetty.io.Content.Source
    {
        /**
         * @return the content type string such as "application/octet-stream" or
         * "application/json;charset=UTF8", or null if the {@code Content-Type}
         * header must not be set
         */
        public default String getContentType()
        {
            return "application/octet-stream";
        }
    }
}
