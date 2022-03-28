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
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.handler.ErrorProcessor;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.MultiMap;
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
 *         Content content = request.readContent();
 *         if (content == null)
 *         {
 *             // The content is not currently available, demand to be called back.
 *             request.demandContent(() -> process(request, response, callback));
 *             return;
 *         }
 *
 *         if (content instanceof Content.Error error)
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
 *         if (content instanceof Content.Trailers trailers)
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
 *         // Normal content, process it.
 *         processContent(content);
 *         // Release the content after processing.
 *         content.release();
 *
 *         // Reached end-of-file?
 *         if (content.isLast())
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
public interface Request extends Attributes, Content.Reader
{
    /**
     * an ID unique within the lifetime scope of the {@link ConnectionMetaData#getId()}).
     * This may be a protocol ID (eg HTTP/2 stream ID) or it may be unrelated to the protocol.
     * @see HttpStream#getId();
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
     */
    HttpURI getHttpURI();

    /**
     * @return the {@code Context} associated with this {@code Request}
     */
    Context getContext();

    /**
     * TODO see discussion in #7713, as this path should probably be canonically encoded - ie everything but %25 and %2F decoded
     * @return The part of the decoded path of the URI after any context path prefix has been removed.
     */
    String getPathInContext();

    /**
     * @return the HTTP headers of this request
     */
    HttpFields getHeaders();

    long getTimeStamp();

    // TODO: see above.
    boolean isSecure();

    // TODO: remove because it's been moved to HttpFields?
    long getContentLength();

    /**
     * <p>Reads a chunk of the request content.</p>
     * <p>The returned {@link Content} may be:</p>
     * <ul>
     * <li>{@code null}, meaning that there will be content to read but it is not yet available</li>
     * <li>a {@link Content.Error} instance, in case of read errors</li>
     * <li>a {@link Content.Trailers} instance, in case of request content trailers</li>
     * <li>a {@link Content} instance, in case of normal request content</li>
     * </ul>
     * <p>When the returned {@code Content} is {@code null}, a call to
     * {@link #demandContent(Runnable)} should be made, to be notified when more
     * request content is available.</p>
     *
     * @see #demandContent(Runnable)
     */
    @Override
    Content readContent();

    /**
     * <p>Demands to notify the given {@code Runnable} when request content is available to be read.</p>
     * <p>It is not mandatory to call this method before a call to {@link #readContent()}.</p>
     * <p>The given {@code Runnable} is notified only once for each invocation of this method,
     * and different invocations of this method may provide the same {@code Runnable}s or
     * different {@code Runnable}s.</p>
     *
     * @see #readContent()
     */
    @Override
    void demandContent(Runnable onContentAvailable);

    void push(MetaData.Request request);

    /**
     * <p>Adds a listener for asynchronous errors.</p>
     * <p>The listener is a predicate function that should return {@code true} to indicate
     * that the function has completed (either successfully or with a failure) the callback
     * received from {@link Handler.Processor#process(Request, Response, Callback)}, or
     * {@code false} otherwise.</p>
     * <p>Listeners are processed in sequence, and the first that returns {@code true}
     * stops the processing of subsequent listeners, which are therefore not invoked.</p>
     *
     * @param onError the predicate function
     * @return true if the listener completes the callback, false otherwise
     */
    boolean addErrorListener(Predicate<Throwable> onError);

    void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper);

    // TODO: why this?
    //  Remove when Request.getContext() is merged.
    default Request getWrapped()
    {
        return null;
    }

    static String getLocalAddr(Request request)
    {
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
        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();
        return -1;
    }

    static String getRemoteAddr(Request request)
    {
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)remote).getAddress();
            String result = address == null
                ? ((InetSocketAddress)remote).getHostString()
                : address.getHostAddress();
            return HostPort.normalizeHost(result);
        }
        return remote.toString();
    }

    static int getRemotePort(Request request)
    {
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return ((InetSocketAddress)remote).getPort();
        return -1;
    }

    static String getServerName(Request request)
    {
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

    static InputStream asInputStream(Request request)
    {
        return Content.asInputStream(request);
    }

    // TODO: use Fields rather than MultiMap!
    static MultiMap<String> extractQueryParameters(Request request)
    {
        MultiMap<String> params = new MultiMap<>();
        String query = request.getHttpURI().getQuery();
        if (StringUtil.isNotBlank(query))
            UrlEncoded.decodeUtf8To(query, params);
        return params;
    }

    @SuppressWarnings("unchecked")
    static List<HttpCookie> getCookies(Request request)
    {
        // TODO modify Request and HttpChannel to be optimised for the known attributes
        List<HttpCookie> cookies = (List<HttpCookie>)request.getAttribute(Request.class.getCanonicalName() + ".Cookies");
        if (cookies != null)
            return cookies;

        // TODO: review whether to store the cookie cache at the connection level, or whether to cache them at all.
        CookieCache cookieCache = (CookieCache)request.getComponents().getCache().get(Request.class.getCanonicalName() + ".CookieCache");
        if (cookieCache == null)
        {
            // TODO compliance listeners?
            cookieCache = new CookieCache(request.getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance(), null);
            request.getComponents().getCache().put(Request.class.getCanonicalName() + ".CookieCache", cookieCache);
        }

        cookies = cookieCache.getCookies(request.getHeaders());
        request.setAttribute(Request.class.getCanonicalName() + ".Cookies", cookies);
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
                location = URIUtil.canonicalURI(location);
            }
            else
            {
                // relative to request
                String path = uri.getPath();
                String parent = (path.endsWith("/")) ? path : URIUtil.parentPath(path);
                location = URIUtil.canonicalURI(URIUtil.addEncodedPaths(parent, location));
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
         * request content (that was forbidden in {@link Handler#handle(Request)}.</p>
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
        public String getPathInContext()
        {
            return getWrapped().getPathInContext();
        }

        @Override
        public HttpFields getHeaders()
        {
            return getWrapped().getHeaders();
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
        public long getContentLength()
        {
            return getWrapped().getContentLength();
        }

        @Override
        public Content readContent()
        {
            return getWrapped().readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            getWrapped().demandContent(onContentAvailable);
        }

        @Override
        public void push(MetaData.Request request)
        {
            getWrapped().push(request);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return getWrapped().addErrorListener(onError);
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
        {
            getWrapped().addHttpStreamWrapper(wrapper);
        }

        @Override
        public Request getWrapped()
        {
            return (Request)super.getWrapped();
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
     * <p>A {@code Request.Wrapper} that is a {@code Request.Processor}.</p>
     * <p>This class wraps both a {@code Request} and a {@code Processor}
     * with the same instance.</p>
     * <p>Typical usage:</p>
     * <pre>
     * class YourHandler extends Handler.Wrapper
     * {
     *     public Processor handle(Request request)
     *     {
     *         // Wrap the request.
     *         WrapperProcessor wrapped = new YourWrapperProcessor(request);
     *
     *         // Delegate processing using the wrapped request to wrap a Processor.
     *         return wrapped.wrapProcessor(super.handle(wrapped));
     *     }
     * }
     * </pre>
     */
    class WrapperProcessor extends Wrapper implements Processor
    {
        private volatile Processor _processor;

        public WrapperProcessor(Request request)
        {
            super(request);
        }

        /**
         * <p>Wraps the given {@code Processor} within this instance and returns this instance.</p>
         *
         * @param processor the {@code Processor} to wrap
         * @return this instance
         */
        public WrapperProcessor wrapProcessor(Processor processor)
        {
            _processor = processor;
            return processor == null ? null : this;
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            Processor processor = _processor;
            if (processor != null)
                processor.process(this, response, callback);
        }
    }
}
