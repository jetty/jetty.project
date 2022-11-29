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

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ErrorProcessor;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>The representation of an HTTP request, for any protocol version (HTTP/1.1, HTTP/2, HTTP/3).</p>
 * <p>A {@code Request} instance is given to a {@link Handler}, that decides whether it handles
 * the request or not.</p>
 * <p>During the handling phase, the {@code Request} APIs can be used, but its content cannot be read.
 * Attempting to read the {@code Request} content during the handling phase results in an
 * {@link IllegalStateException} to be thrown.</p>
 * <p>A {@code Handler} that handles the request returns a {@link Processor}, that is then invoked
 * to process the request and the response (the processing phase).</p>
 * <p>Only during the processing phase the {@code Request} content can be read.</p>
 * <p>The typical idiom to read request content is the following:</p>
 * <pre>{@code
 * public void process(Request request, Response response, Callback callback)
 * {
 *     while (true)
 *     {
 *         Content.Chunk chunk = request.read();
 *         if (chunk == null)
 *         {
 *             // The chunk is not currently available, demand to be called back.
 *             request.demand(() -> process(request, response, callback));
 *             return;
 *         }
 *
 *         if (chunk instanceof Content.Chunk.Error error)
 *         {
 *             Throwable failure = error.getCause();
 *
 *             // Handle errors.
 *             // Mark the processing as complete, either generating a custom
 *             // response and succeeding the callback, or failing the callback.
 *             callback.failed(failure);
 *             return;
 *         }
 *
 *         if (chunk instanceof Trailers trailers)
 *         {
 *             HttpFields fields = trailers.getTrailers();
 *
 *             // Handle trailers.
 *
 *             // Generate a response.
 *
 *             // Mark the processing as complete.
 *             callback.succeeded();
 *
 *             return;
 *         }
 *
 *         // Normal chunk, process it.
 *         processChunk(chunk);
 *         // Release the content after processing.
 *         chunk.release();
 *
 *         // Reached end-of-file?
 *         if (chunk.isLast())
 *         {
 *             // Generate a response.
 *
 *             // Mark the processing as complete.
 *             callback.succeeded();
 *
 *             return;
 *         }
 *     }
 * }
 * }</pre>
 */
public interface Request extends Attributes, Content.Source
{
    List<Locale> __defaultLocale = Collections.singletonList(Locale.getDefault());
    String CACHE_ATTRIBUTE = Request.class.getCanonicalName() + ".CookieCache";
    String COOKIE_ATTRIBUTE = Request.class.getCanonicalName() + ".Cookies";

    /**
     * <p>This method must be called withing the scope of a call to
     * {@link Handler#process(Request, Response, Callback)} to indicate that the request
     * has been accepted for processing and that the callback will ultimately be called, once the
     * response has been generated. Until accept has been called, the body of the request cannot
     * be read, nor the response body written, nor the callback called.
     */
    void accept();

    boolean isAccepted();

    /**
     * an ID unique within the lifetime scope of the {@link ConnectionMetaData#getId()}).
     * This may be a protocol ID (eg HTTP/2 stream ID) or it may be unrelated to the protocol.
     *
     * @see HttpStream#getId()
     */
    String getId();

    /**
     * @return the {@link Components} to be used with this request.
     */
    Components getComponents();

    /**
     * @return the {@code ConnectionMetaData} associated to this request
     */
    ConnectionMetaData getConnectionMetaData();

    /**
     * @return the HTTP method of this request
     */
    String getMethod();

    /**
     * @return the HTTP URI of this request
     * @see #getContextPath(Request)
     * @see #getPathInContext(Request)
     */
    HttpURI getHttpURI();

    /**
     * @return the {@code Context} associated with this {@code Request}
     */
    Context getContext();

    /**
     * <p>Returns the context path of this Request.</p>
     * <p>This is equivalent to {@code request.getContext().getContextPath()}.</p>
     *
     * @param request The request to get the context path from.
     * @return The contextPath of the request.
     * @see Context#getContextPath()
     */
    static String getContextPath(Request request)
    {
        return request.getContext().getContextPath();
    }

    /**
     * <p>Returns the canonically encoded path of the URI, scoped to the current context.</p>
     * <p>For example, when the request has a {@link Context} with {@code contextPath=/ctx} and the request's
     * {@link HttpURI} canonical path is {@code canonicalPath=/ctx/foo}, then {@code pathInContext=/foo}.</p>
     *
     * @return The part of the canonically encoded path of the URI after any context path prefix has been removed.
     * @see HttpURI#getCanonicalPath()
     * @see Context#getContextPath()
     */
    static String getPathInContext(Request request)
    {
        return request.getContext().getPathInContext(request.getHttpURI().getCanonicalPath());
    }

    /**
     * @return the HTTP headers of this request
     */
    HttpFields getHeaders();

    /**
     * @return the HTTP trailers of this request, or {@code null} if they are not present
     */
    HttpFields getTrailers();

    long getTimeStamp();

    // TODO: see above.
    boolean isSecure();

    /**
     * {@inheritDoc}
     * <p>In addition, the returned {@link Content.Chunk} may be a
     * {@link Trailers} instance, in case of request content trailers.</p>
     */
    @Override
    Content.Chunk read();

    /**
     * <p>Pushes the given {@code resource} to the client.</p>
     *
     * @param resource the resource to push
     * @throws UnsupportedOperationException if the push functionality is not supported
     * @see ConnectionMetaData#isPushSupported()
     */
    default void push(MetaData.Request resource)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>Adds a listener for asynchronous errors.</p>
     * <p>The listener is a predicate function that should return {@code true} to indicate
     * that the function has completed (either successfully or with a failure) the callback
     * received from {@link Handler.Processor#doProcess(Request, Response, Callback)}, or
     * {@code false} otherwise.</p>
     * <p>Listeners are processed in sequence, and the first that returns {@code true}
     * stops the processing of subsequent listeners, which are therefore not invoked.</p>
     *
     * @param onError the predicate function
     * @return true if the listener completes the callback, false otherwise
     */
    boolean addErrorListener(Predicate<Throwable> onError);

    TunnelSupport getTunnelSupport();

    void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper);

    static String getLocalAddr(Request request)
    {
        if (request == null)
            return null;
        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)local).getAddress();
            String result = address == null
                ? ((InetSocketAddress)local).getHostString()
                : address.getHostAddress();
            return HostPort.normalizeHost(result);
        }
        return local.toString();
    }

    static int getLocalPort(Request request)
    {
        if (request == null)
            return -1;
        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();
        return -1;
    }

    static String getRemoteAddr(Request request)
    {
        if (request == null)
            return null;
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress)
        {
            if (inetSocketAddress.isUnresolved())
                return inetSocketAddress.getHostString();

            InetAddress address = inetSocketAddress.getAddress();
            String result = address == null
                ? inetSocketAddress.getHostString()
                : address.getHostAddress();
            return HostPort.normalizeHost(result);
        }
        return remote.toString();
    }

    static int getRemotePort(Request request)
    {
        if (request == null)
            return -1;
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return ((InetSocketAddress)remote).getPort();
        return -1;
    }

    static String getServerName(Request request)
    {
        if (request == null)
            return null;

        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority())
            return HostPort.normalizeHost(uri.getHost());

        HostPort authority = request.getConnectionMetaData().getServerAuthority();
        if (authority != null)
            return HostPort.normalizeHost(authority.getHost());

        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return HostPort.normalizeHost(((InetSocketAddress)local).getHostString());

        return local.toString();
    }

    static int getServerPort(Request request)
    {
        if (request == null)
            return -1;
        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority() && uri.getPort() > 0)
            return uri.getPort();

        HostPort authority = request.getConnectionMetaData().getServerAuthority();
        if (authority != null && authority.getPort() > 0)
            return authority.getPort();

        if (authority == null)
        {
            SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
            if (local instanceof InetSocketAddress)
                return ((InetSocketAddress)local).getPort();
        }

        HttpScheme scheme = HttpScheme.CACHE.get(request.getHttpURI().getScheme());
        if (scheme != null)
            return scheme.getDefaultPort();

        return -1;
    }

    static List<Locale> getLocales(Request request)
    {
        HttpFields fields = request.getHeaders();
        if (fields == null)
            return __defaultLocale;

        List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // handle no locale
        if (acceptable.isEmpty())
            return __defaultLocale;

        return acceptable.stream().map(language ->
        {
            language = HttpField.stripParameters(language);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0, dash).trim();
            }
            return new Locale(language, country);
        }).collect(Collectors.toList());
    }

    // TODO: consider inline and remove.
    static InputStream asInputStream(Request request)
    {
        return Content.Source.asInputStream(request);
    }

    static Fields extractQueryParameters(Request request)
    {
        Fields fields = new Fields(true);
        String query = request.getHttpURI().getQuery();
        if (StringUtil.isNotBlank(query))
            UrlEncoded.decodeUtf8To(query, fields);
        return fields;
    }

    static Fields extractQueryParameters(Request request, Charset charset)
    {
        Fields fields = new Fields(true);
        String query = request.getHttpURI().getQuery();
        if (StringUtil.isNotBlank(query))
            UrlEncoded.decodeTo(query, fields::add, charset);
        return fields;
    }

    @SuppressWarnings("unchecked")
    static List<HttpCookie> getCookies(Request request)
    {
        // TODO modify Request and HttpChannel to be optimised for the known attributes
        List<HttpCookie> cookies = (List<HttpCookie>)request.getAttribute(COOKIE_ATTRIBUTE);
        if (cookies != null)
            return cookies;

        // TODO: review whether to store the cookie cache at the connection level, or whether to cache them at all.
        CookieCache cookieCache = (CookieCache)request.getComponents().getCache().getAttribute(CACHE_ATTRIBUTE);
        if (cookieCache == null)
        {
            // TODO compliance listeners?
            cookieCache = new CookieCache(request.getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance(), null);
            request.getComponents().getCache().setAttribute(CACHE_ATTRIBUTE, cookieCache);
        }

        cookies = cookieCache.getCookies(request.getHeaders());
        request.setAttribute(COOKIE_ATTRIBUTE, cookies);
        return cookies;
    }

    /**
     * Common point to generate a proper "Location" header for redirects.
     *
     * @param request the request the redirect should be based on (needed when relative locations are provided, so that
     * server name, scheme, port can be built out properly)
     * @param location the location URL to redirect to (can be a relative path)
     * @return the full redirect "Location" URL (including scheme, host, port, path, etc...)
     */
    static String toRedirectURI(Request request, String location)
    {
        // TODO write some tests for this
        if (!URIUtil.hasScheme(location) && !request.getConnectionMetaData().getHttpConfiguration().isRelativeRedirectAllowed())
        {
            StringBuilder url = new StringBuilder(128);
            HttpURI uri = request.getHttpURI();
            URIUtil.appendSchemeHostPort(url, uri.getScheme(), Request.getServerName(request), Request.getServerPort(request));

            if (location.startsWith("/"))
            {
                // absolute in context
                location = URIUtil.normalizePathQuery(location);
            }
            else
            {
                // relative to request
                String path = uri.getPath();
                String parent = (path.endsWith("/")) ? path : URIUtil.parentPath(path);
                location = URIUtil.normalizePathQuery(URIUtil.addEncodedPaths(parent, location));
                if (location != null && !location.startsWith("/"))
                    url.append('/');
            }

            if (location == null)
                throw new IllegalStateException("redirect path cannot be above root");
            url.append(location);

            location = url.toString();
        }
        // TODO do we need to do request relative without scheme?

        return location;
    }

    /**
     * <p>A processor for an HTTP request and response.</p>
     * <p>The processing typically involves reading the request content (if any) and producing a response.</p>
     */
    @FunctionalInterface
    interface Processor extends Invocable
    {
        /**
         * <p>Invoked to process the given HTTP request and response.</p>
         * <p>The processing <em>must</em> be concluded by completing the given callback.</p>
         * <p>The processing may be asynchronous, i.e. this method may return early and
         * complete the given callback later, possibly from a different thread.</p>
         * <p>Within an implementation of this method it is possible to read the
         * request content (that was forbidden in {@link Handler#process(Request, Response, Callback)}.</p>
         * <p>Exceptions thrown by this method are processed by an {@link ErrorProcessor},
         * if present, otherwise a default HTTP 500 error is generated and the
         * callback completed while writing the error response.</p>
         * <p>The simplest implementation is:</p>
         * <pre>
         * public void process(Request request, Response response, Callback callback)
         * {
         *     // Implicitly respond with 200 OK.
         *     callback.succeeded();
         * }
         * </pre>
         * <p>A HelloWorld implementation is:</p>
         * <pre>
         * public void process(Request request, Response response, Callback callback)
         * {
         *     // The callback is completed when the write completes.
         *     response.write(true, callback, "hello, world!");
         * }
         * </pre>
         *
         * @param request the HTTP request to process
         * @param response the HTTP response to process
         * @param callback the callback to complete when the processing is complete
         * @throws Exception if there is a failure during the processing
         */
        void process(Request request, Response response, Callback callback) throws Exception;
    }

    /**
     * <p>A wrapper for {@code Request} instances.</p>
     */
    class Wrapper extends Attributes.Wrapper implements Request
    {
        public Wrapper(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public void accept()
        {
            getWrapped().accept();
        }

        @Override
        public boolean isAccepted()
        {
            return getWrapped().isAccepted();
        }

        @Override
        public String getId()
        {
            return getWrapped().getId();
        }

        @Override
        public Components getComponents()
        {
            return getWrapped().getComponents();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return getWrapped().getConnectionMetaData();
        }

        @Override
        public String getMethod()
        {
            return getWrapped().getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return getWrapped().getHttpURI();
        }

        @Override
        public Context getContext()
        {
            return getWrapped().getContext();
        }

        @Override
        public HttpFields getHeaders()
        {
            return getWrapped().getHeaders();
        }

        @Override
        public HttpFields getTrailers()
        {
            return getWrapped().getTrailers();
        }

        @Override
        public long getTimeStamp()
        {
            return getWrapped().getTimeStamp();
        }

        @Override
        public boolean isSecure()
        {
            return getWrapped().isSecure();
        }

        @Override
        public long getLength()
        {
            return getWrapped().getLength();
        }

        @Override
        public Content.Chunk read()
        {
            return getWrapped().read();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            getWrapped().demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure)
        {
            getWrapped().fail(failure);
        }

        @Override
        public void push(MetaData.Request resource)
        {
            getWrapped().push(resource);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return getWrapped().addErrorListener(onError);
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return getWrapped().getTunnelSupport();
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
            getWrapped().addHttpStreamWrapper(wrapper);
        }

        @Override
        public Request getWrapped()
        {
            return (Request)super.getWrapped();
        }
    }

    class AcceptingWrapper extends Wrapper
    {
        private final AtomicBoolean _accepted = new AtomicBoolean();

        public AcceptingWrapper(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public void accept()
        {
            if (!_accepted.compareAndSet(false, true))
                throw new IllegalStateException("already accepted");
        }

        @Override
        public boolean isAccepted()
        {
            return _accepted.get();
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Request.Wrapper> T as(Request request, Class<T> type)
    {
        while (request instanceof Request.Wrapper wrapper)
        {
            if (type.isInstance(wrapper))
                return (T)wrapper;
            request = wrapper.getWrapped();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T extends Request.Wrapper, R> R get(Request request, Class<T> type, Function<T, R> getter)
    {
        while (request instanceof Request.Wrapper wrapper)
        {
            if (type.isInstance(wrapper))
                return getter.apply((T)wrapper);
            request = wrapper.getWrapped();
        }
        return null;
    }

    static Request unWrap(Request request)
    {
        while (request instanceof Request.Wrapper wrapped)
        {
            request = wrapped.getWrapped();
        }
        return request;
    }

    static long getContentBytesRead(Request request)
    {
        Request originalRequest = unWrap(request);
        if (originalRequest instanceof HttpChannelState.ChannelRequest channelRequest)
            return channelRequest.getContentBytesRead();
        return -1;
    }

    /**
     * <p>Creates a new {@link HttpURI} from the given Request's HttpURI and the given path in context.</p>
     * <p>For example, for {@code contextPath=/ctx}, {@code request.httpURI=http://host/ctx/path?a=b}, and
     * {@code newPathInContext=/newPath}, the returned HttpURI is {@code http://host/ctx/newPath?a=b}.</p>
     *
     * @param request The request to base the new HttpURI on.
     * @param newPathInContext The new path in context for the new HttpURI
     * @return A new immutable HttpURI with the path in context replaced, but query string and path
     * parameters retained.
     */
    static HttpURI newHttpURIFrom(Request request, String newPathInContext)
    {
        return HttpURI.build(request.getHttpURI())
            .path(URIUtil.addPaths(getContextPath(request), newPathInContext))
            .asImmutable();
    }
}
