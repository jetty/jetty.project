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

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>The representation of an HTTP request, for any protocol version (HTTP/1.1, HTTP/2, HTTP/3).</p>
 * <p>The typical idiom to read request content is the following:</p>
 * <pre>{@code
 * public boolean handle(Request request, Response response, Callback callback)
 * {
 *     // Reject requests not appropriate for this handler.
 *     if (!request.getHttpURI().getPath().startsWith("/yourPath"))
 *         return false;
 *
 *     while (true)
 *     {
 *         Content.Chunk chunk = request.read();
 *         if (chunk == null)
 *         {
 *             // The chunk is not currently available, demand to be called back.
 *             request.demand(() -> handle(request, response, callback));
 *             return true;
 *         }
 *
 *         if (Content.Chunk.isError(chunk))
 *         {
 *             Throwable failure = error.getCause();
 *
 *             // Handle errors.
 *             // If the chunk is not last, then the error can be ignored and reading can be tried again.
 *             // Otherwise, if the chunk is last, or we do not wish to ignore a non-last error, then
 *             // mark the handling as complete, either generating a custom
 *             // response and succeeding the callback, or failing the callback.
 *             callback.failed(failure);
 *             return true;
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
 *             // Mark the handling as complete.
 *             callback.succeeded();
 *
 *             return true;
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
 *             // Mark the handling as complete.
 *             callback.succeeded();
 *
 *             return true;
 *         }
 *     }
 * }
 * }</pre>
 */
public interface Request extends Attributes, Content.Source
{
    String CACHE_ATTRIBUTE = Request.class.getCanonicalName() + ".CookieCache";
    String COOKIE_ATTRIBUTE = Request.class.getCanonicalName() + ".Cookies";
    List<Locale> DEFAULT_LOCALES = List.of(Locale.getDefault());

    /**
     * an ID unique within the lifetime scope of the {@link ConnectionMetaData#getId()}).
     * This may be a protocol ID (e.g. HTTP/2 stream ID) or it may be unrelated to the protocol.
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
     * {@inheritDoc}
     * @param demandCallback the demand callback to invoke when there is a content chunk available.
     *                       In addition to the invocation guarantees of {@link Content.Source#demand(Runnable)},
     *                       this implementation serializes the invocation of the {@code Runnable} with
     *                       invocations of any {@link Response#write(boolean, ByteBuffer, Callback)}
     *                       {@code Callback} invocations.
     * @see Content.Source#demand(Runnable)
     */
    @Override
    void demand(Runnable demandCallback);

    /**
     * @return the HTTP trailers of this request, or {@code null} if they are not present
     */
    HttpFields getTrailers();

    /**
     * <p>Get the millisecond timestamp at which the request was created, obtained with {@link System#currentTimeMillis()}.
     * This method should be used for wall clock time, rather than {@link #getHeadersNanoTime()},
     * which is appropriate for measuring latencies.</p>
     * @return The timestamp that the request was received/created in milliseconds
     */
    static long getTimeStamp(Request request)
    {
        return System.currentTimeMillis() - NanoTime.millisSince(request.getHeadersNanoTime());
    }

    /**
     * <p>Get the nanoTime at which the request arrived to a connector, obtained via {@link System#nanoTime()}.
     * This method can be used when measuring latencies.</p>
     * @return The nanoTime at which the request was received/created in nanoseconds
     */
    long getBeginNanoTime();

    /**
     * <p>Get the nanoTime at which the request headers were parsed, obtained via {@link System#nanoTime()}.
     * This method can be used when measuring latencies.</p>
     * @return The nanoTime at which the request was ready in nanoseconds
     */
    long getHeadersNanoTime();

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
     * Consume any available content. This bypasses any request wrappers to process the content in
     * {@link Request#read()} and reads directly from the {@link HttpStream}. This reads until
     * there is no content currently available, or it reaches EOF.
     * The {@link HttpConfiguration#setMaxUnconsumedRequestContentReads(int)} configuration can be used
     * to configure how many reads will be attempted by this method.
     * @return true if the content was fully consumed.
     */
    boolean consumeAvailable();

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
     * <p>Adds a listener for idle timeouts.</p>
     * <p>The listener is a predicate function that should return {@code true} to indicate
     * that the idle timeout should be handled by the container as a hard failure
     * (see {@link #addFailureListener(Consumer)}); or {@code false} to ignore that specific timeout and for another timeout
     * to occur after another idle period.</p>
     * <p>Any pending {@link #demand(Runnable)} or {@link Response#write(boolean, ByteBuffer, Callback)} operations
     * are not affected by this call. Applications need to be mindful of any such pending operations if attempting
     * to make new operations.</p>
     * <p>Listeners are processed in sequence, and the first that returns {@code true}
     * stops the processing of subsequent listeners, which are therefore not invoked.</p>
     *
     * @param onIdleTimeout the predicate function
     * @see #addFailureListener(Consumer)
     */
    void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout);

    /**
     * <p>Adds a listener for asynchronous hard errors.</p>
     * <p>When a listener is called, the effects of the error will already have taken place:</p>
     * <ul>
     *     <li>Pending {@link #demand(Runnable)} will be woken up.</li>
     *     <li>Calls to {@link #read()} will return the {@code Throwable}.</li>
     *     <li>Pending and new {@link Response#write(boolean, ByteBuffer, Callback)} calls will be failed by
     *     calling {@link Callback#failed(Throwable)} on the callback passed to {@code write(...)}.</li>
     *     <li>Any call to {@link Callback#succeeded()} on the callback passed to
     *     {@link Handler#handle(Request, Response, Callback)} will effectively be a call to {@link Callback#failed(Throwable)}
     *     with the notified {@link Throwable}.</li>
     * </ul>
     * <p>Listeners are processed in sequence. When all listeners are invoked then {@link Callback#failed(Throwable)}
     * will be called on the callback passed to {@link Handler#handle(Request, Response, Callback)}.</p>
     *
     * @param onFailure the consumer function
     * @see #addIdleTimeoutListener(Predicate)
     */
    void addFailureListener(Consumer<Throwable> onFailure);

    TunnelSupport getTunnelSupport();

    void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper);

    /**
     * <p>Get a {@link Session} associated with the request.
     * Sessions may not be supported by a given configuration, in which case
     * {@code null} will be returned.</p>
     * @param create True if the session should be created for the request.
     * @return The session associated with the request or {@code null}.
     */
    Session getSession(boolean create);

    /**
     * Returns a copy of the request that throws {@link UnsupportedOperationException}
     * from all mutative methods.
     * @return a copy of the request
     */
    static Request asReadOnly(Request request)
    {
        return new Request.Wrapper(request)
        {
            @Override
            public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public Content.Chunk read()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public void demand(Runnable demandCallback)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public void fail(Throwable failure)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

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
        return local == null ? null : local.toString();
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
        return remote == null ? null : remote.toString();
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

    /**
     * Get the logical name the request was sent to, which may be from the authority of the
     * request; the configured server authority; the actual network name of the server;
     * @param request The request to get the server name of
     * @return The logical server name or null if it cannot be determined.
     */
    static String getServerName(Request request)
    {
        if (request == null)
            return null;

        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority())
            return HostPort.normalizeHost(uri.getHost());

        HostPort authority = request.getConnectionMetaData().getServerAuthority();
        if (authority != null)
            return authority.getHost();

        return null;
    }

    /**
     * Get the logical port a request was received on, which may be from the authority of the request; the
     * configured server authority; the default port for the scheme; or the actual network port.
     * @param request The request to get the port of
     * @return The port for the request if it can be determined, otherwise -1
     */
    static int getServerPort(Request request)
    {
        if (request == null)
            return -1;

        // Does the request have an explicit port?
        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority() && uri.getPort() > 0)
            return uri.getPort();

        // Is there a configured server authority?
        HostPort authority = request.getConnectionMetaData().getHttpConfiguration().getServerAuthority();
        if (authority != null && authority.getPort() > 0)
            return authority.getPort();

        // Is there a scheme with a default port?
        HttpScheme scheme = HttpScheme.CACHE.get(request.getHttpURI().getScheme());
        if (scheme != null && scheme.getDefaultPort() > 0)
            return scheme.getDefaultPort();

        // Is there a local port?
        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress inetSocketAddress && inetSocketAddress.getPort() > 0)
            return inetSocketAddress.getPort();

        return -1;
    }

    static List<Locale> getLocales(Request request)
    {
        HttpFields fields = request.getHeaders();
        if (fields == null)
            return DEFAULT_LOCALES;

        List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // return sorted list of locals, with known locales in quality order before unknown locales in quality order
        return switch (acceptable.size())
        {
            case 0 -> DEFAULT_LOCALES;
            case 1 -> List.of(Locale.forLanguageTag(acceptable.get(0)));
            default ->
            {
                List<Locale> locales = acceptable.stream().map(Locale::forLanguageTag).toList();
                List<Locale> known = locales.stream().filter(MimeTypes::isKnownLocale).toList();
                if (known.size() == locales.size())
                    yield locales; // All locales are known
                List<Locale> unknown = locales.stream().filter(l -> !MimeTypes.isKnownLocale(l)).toList();
                locales = new ArrayList<>(known);
                locales.addAll(unknown);
                yield locales; // List of known locales before unknown locales
            }
        };
    }

    // TODO: consider inline and remove.
    static InputStream asInputStream(Request request)
    {
        return Content.Source.asInputStream(request);
    }

    static Fields extractQueryParameters(Request request)
    {
        String query = request.getHttpURI().getQuery();
        if (StringUtil.isBlank(query))
            return Fields.EMPTY;
        Fields fields = new Fields(true);
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

    static Fields getParameters(Request request) throws Exception
    {
        Fields queryFields = Request.extractQueryParameters(request);
        Fields formFields = FormFields.from(request).get();
        return Fields.combine(queryFields, formFields);
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
     * <p>A handler for an HTTP request and response.</p>
     * <p>The handling typically involves reading the request content (if any) and producing a response.</p>
     */
    @FunctionalInterface
    interface Handler extends Invocable
    {
        /**
         * <p>Invoked to handle the passed HTTP request and response.</p>
         * <p>The request is accepted by returning true, then handling <em>must</em> be concluded by
         * completing the passed callback. The handling may be asynchronous, i.e. this method may return true and
         * complete the given callback later, possibly from a different thread.  If this method returns false,
         * then the callback must not be invoked and any mutation on the response reversed.</p>
         * <p>Exceptions thrown by this method may be subsequently handled by an error {@link Request.Handler},
         * if present, otherwise a default HTTP 500 error is generated and the
         * callback completed while writing the error response.</p>
         * <p>The simplest implementation is:</p>
         * <pre>
         * public boolean handle(Request request, Response response, Callback callback)
         * {
         *     callback.succeeded();
         *     return true;
         * }
         * </pre>
         * <p>A HelloWorld implementation is:</p>
         * <pre>
         * public boolean handle(Request request, Response response, Callback callback)
         * {
         *     response.write(true, ByteBuffer.wrap("Hello World\n".getBytes(StandardCharsets.UTF_8)), callback);
         *     return true;
         * }
         * </pre>
         *
         * @param request the HTTP request to handle
         * @param response the HTTP response to handle
         * @param callback the callback to complete when the handling is complete
         * @return True if and only if the request will be handled, a response generated and the callback eventually called.
         *         This may occur within the scope of the call to this method, or asynchronously some time later. If false
         *         is returned, then this method must not generate a response, nor complete the callback.
         * @throws Exception if there is a failure during the handling. Catchers cannot assume that the callback will be
         *                   called and thus should attempt to complete the request as if a false had been returned.
         */
        boolean handle(Request request, Response response, Callback callback) throws Exception;
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
        public long getBeginNanoTime()
        {
            return getWrapped().getBeginNanoTime();
        }

        @Override
        public long getHeadersNanoTime()
        {
            return getWrapped().getHeadersNanoTime();
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
        public boolean consumeAvailable()
        {
            return getWrapped().consumeAvailable();
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
        public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
        {
            getWrapped().addIdleTimeoutListener(onIdleTimeout);
        }

        @Override
        public void addFailureListener(Consumer<Throwable> onFailure)
        {
            getWrapped().addFailureListener(onFailure);
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
        public Session getSession(boolean create)
        {
            return getWrapped().getSession(create);
        }

        @Override
        public Request getWrapped()
        {
            return (Request)super.getWrapped();
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Request> T as(Request request, Class<T> type)
    {
        while (request != null)
        {
            if (type.isInstance(request))
                return (T)request;
            request = request instanceof Request.Wrapper wrapper ? wrapper.getWrapped() : null;
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
     * @param newEncodedPathInContext The new path in context for the new HttpURI
     * @return A new immutable HttpURI with the path in context replaced, but query string and path
     * parameters retained.
     */
    static HttpURI newHttpURIFrom(Request request, String newEncodedPathInContext)
    {
        return HttpURI.build(request.getHttpURI())
            .path(URIUtil.addPaths(getContextPath(request), newEncodedPathInContext))
            .asImmutable();
    }

    /**
     * @param request The request to enquire.
     * @return the minimal {@link AuthenticationState} of the request, or null if no authentication in process.
     */
    static AuthenticationState getAuthenticationState(Request request)
    {
        if (request.getAttribute(AuthenticationState.class.getName()) instanceof AuthenticationState authenticationState)
            return authenticationState;
        return null;
    }

    /**
     * @param request The request to enquire.
     * @param state the {@link AuthenticationState} of the request, or null if no authentication in process.
     */
    static void setAuthenticationState(Request request, AuthenticationState state)
    {
        request.setAttribute(AuthenticationState.class.getName(), state);
    }

    /**
     * A minimal Authentication interface, primarily used for logging.  It is implemented by the
     * {@code jetty-security} module's {@code AuthenticationState} to provide full authentication services.
     */
    interface AuthenticationState
    {
        /**
         * @return The authenticated user {@link Principal}, or null if the Authentication is in a non-authenticated state.
         */
        default Principal getUserPrincipal()
        {
            return null;
        }
    }
}
