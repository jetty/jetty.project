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
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;
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
     * @return the full URI of this request such as "http://host:port/path?param=1"
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
     * @param name the name of the header
     * @param value the value of the header
     * @return this request object
     * @see #header(HttpHeader, String)
     * @deprecated use {@link #headers(Consumer)} instead
     */
    @Deprecated
    Request header(String name, String value);

    /**
     * <p>Adds the given {@code value} to the specified {@code header}.</p>
     * <p>Multiple calls with the same parameters will add multiple values;
     * use the value {@code null} to remove the header completely.</p>
     *
     * @param header the header name
     * @param value the value of the header
     * @return this request object
     * @deprecated use {@link #headers(Consumer)} instead
     */
    @Deprecated
    Request header(HttpHeader header, String value);

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
     * @param listener an asynchronous listener for response content events
     * @return this request object
     */
    Request onResponseContentDemanded(Response.DemandedContentListener listener);

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
    boolean abort(Throwable cause);

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
     * <p>A reactive model to produce request content, similar to {@link java.util.concurrent.Flow.Publisher}.</p>
     * <p>Implementations receive the content consumer via {@link #subscribe(Consumer, boolean)},
     * and return a {@link Subscription} as the link between producer and consumer.</p>
     * <p>Content producers must notify content to the consumer only if there is demand.</p>
     * <p>Content consumers can generate demand for content by invoking {@link Subscription#demand()}.</p>
     * <p>Content production must follow this algorithm:</p>
     * <ul>
     *   <li>the first time content is demanded
     *   <ul>
     *     <li>when the content is not available =&gt; produce an empty content</li>
     *     <li>when the content is available:
     *       <ul>
     *         <li>when {@code emitInitialContent == false} =&gt; produce an empty content</li>
     *         <li>when {@code emitInitialContent == true} =&gt; produce the content</li>
     *       </ul>
     *     </li>
     *   </ul>
     *   </li>
     *   <li>the second and subsequent times content is demanded
     *     <ul>
     *       <li>when the content is not available =&gt; do not produce content</li>
     *       <li>when the content is available =&gt; produce the content</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @see #subscribe(Consumer, boolean)
     */
    public interface Content
    {
        /**
         * @return the content type string such as "application/octet-stream" or
         * "application/json;charset=UTF8", or null if no content type must be set
         */
        public default String getContentType()
        {
            return "application/octet-stream";
        }

        /**
         * @return the content length, if known, or -1 if the content length is unknown
         */
        public default long getLength()
        {
            return -1;
        }

        /**
         * <p>Whether this content producer can produce exactly the same content more
         * than once.</p>
         * <p>Implementations should return {@code true} only if the content can be
         * produced more than once, which means that {@link #subscribe(Consumer, boolean)}
         * may be called again.</p>
         * <p>The {@link HttpClient} implementation may use this method in particular
         * cases where it detects that it is safe to retry a request that failed.</p>
         *
         * @return whether the content can be produced more than once
         */
        public default boolean isReproducible()
        {
            return false;
        }

        /**
         * <p>Initializes this content producer with the content consumer, and with
         * the indication of whether initial content, if present, must be emitted
         * upon the initial demand of content (to support delaying the send of the
         * request content in case of {@code Expect: 100-Continue} when
         * {@code emitInitialContent} is {@code false}).</p>
         *
         * @param consumer the content consumer to invoke when there is demand for content
         * @param emitInitialContent whether to emit initial content, if present
         * @return the Subscription that links this producer to the consumer
         */
        public Subscription subscribe(Consumer consumer, boolean emitInitialContent);

        /**
         * <p>Fails this request content, possibly failing and discarding accumulated
         * content that was not demanded.</p>
         * <p>The failure may be notified to the consumer at a later time, when the
         * consumer demands for content.</p>
         * <p>Typical failure: the request being aborted by user code, or idle timeouts.</p>
         *
         * @param failure the reason of the failure
         */
        public default void fail(Throwable failure)
        {
        }

        /**
         * <p>A reactive model to consume request content, similar to {@link java.util.concurrent.Flow.Subscriber}.</p>
         * <p>Callback methods {@link #onContent(ByteBuffer, boolean, Callback)} and {@link #onFailure(Throwable)}
         * are invoked in strict sequential order and never concurrently, although possibly by different threads.</p>
         */
        public interface Consumer
        {
            /**
             * <p>Callback method invoked by the producer when there is content available
             * <em>and</em> there is demand for content.</p>
             * <p>The {@code callback} is associated with the {@code buffer} to
             * signal when the content buffer has been consumed.</p>
             * <p>Failing the {@code callback} does not have any effect on content
             * production. To stop the content production, the consumer must call
             * {@link Subscription#fail(Throwable)}.</p>
             * <p>In case an exception is thrown by this method, it is equivalent to
             * a call to {@link Subscription#fail(Throwable)}.</p>
             *
             * @param buffer the content buffer to consume
             * @param last whether it's the last content
             * @param callback a callback to invoke when the content buffer is consumed
             */
            public void onContent(ByteBuffer buffer, boolean last, Callback callback);

            /**
             * <p>Callback method invoked by the producer when it failed to produce content.</p>
             * <p>Typical failure: a producer getting an exception while reading from an
             * {@link InputStream} to produce content.</p>
             *
             * @param failure the reason of the failure
             */
            public default void onFailure(Throwable failure)
            {
            }
        }

        /**
         * <p>The link between a content producer and a content consumer.</p>
         * <p>Content consumers can demand more content via {@link #demand()},
         * or ask the content producer to stop producing content via
         * {@link #fail(Throwable)}.</p>
         */
        public interface Subscription
        {
            /**
             * <p>Demands more content, which eventually results in
             * {@link Consumer#onContent(ByteBuffer, boolean, Callback)} to be invoked.</p>
             */
            public void demand();

            /**
             * <p>Fails the subscription, notifying the content producer to stop producing
             * content.</p>
             * <p>Typical failure: a proxy consumer waiting for more content (or waiting
             * to demand content) that is failed by an error response from the server.</p>
             *
             * @param failure the reason of the failure
             */
            public default void fail(Throwable failure)
            {
            }
        }
    }
}
