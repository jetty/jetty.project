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
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.CompletionStreamWrapper;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    Logger LOG = LoggerFactory.getLogger(Request.class);

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
     * @return the {@link Components} to be used with this {@code Request}.
     */
    Components getComponents();

    /**
     * @return the {@code ConnectionMetaData} associated to this {@code Request}
     */
    ConnectionMetaData getConnectionMetaData();

    /**
     * @return the HTTP method of this {@code Request}
     */
    String getMethod();

    /**
     * @return the HTTP URI of this {@code Request}
     * @see #getContextPath(Request)
     * @see #getPathInContext(Request)
     */
    HttpURI getHttpURI();

    /**
     * Get the {@link Context} associated with this {@code Request}.
     * <p>Note that a {@code Request} should always have an associated {@link Context} since if the
     * {@code Request} is not being handled by a {@link org.eclipse.jetty.server.handler.ContextHandler} then
     * the {@link Context} from {@link Server#getContext()} will be used.
     * @return the {@link Context} associated with this {@code Request}. Never {@code null}.
     * @see org.eclipse.jetty.server.handler.ContextHandler
     * @see Server#getContext()
     */
    Context getContext();

    /**
     * Get the context path of this {@code Request}.
     * This is equivalent to {@code request.getContext().getContextPath()}.
     *
     * @param request The request to get the context path from.
     * @return The encoded context path of the {@link Context} or {@code null}.
     * @see #getContext()
     * @see Context#getContextPath()
     * @see Server#getContext()
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
     * @return the HTTP headers of this {@code Request}
     */
    HttpFields getHeaders();

    /**
     * {@inheritDoc}
     * @param demandCallback the demand callback to invoke when there is a content chunk available.
     * @see Content.Source#demand(Runnable)
     */
    @Override
    void demand(Runnable demandCallback);

    /**
     * @return the HTTP trailers of this {@code Request}, or {@code null} if they are not present
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
     * that the idle timeout should be handled by the container as a fatal failure
     * (see {@link #addFailureListener(Consumer)}); or {@code false} to ignore that specific
     * timeout and for another timeout to occur after another idle period.</p>
     * <p>Idle timeout listeners are only invoked if there are no pending
     * {@link #demand(Runnable)} or {@link Response#write(boolean, ByteBuffer, Callback)}
     * operations.</p>
     * <p>Listeners are processed in the same order they are added, and the first that
     * returns {@code true} stops the processing of subsequent listeners, which are
     * therefore not invoked.</p>
     *
     * @param onIdleTimeout the idle timeout listener as a predicate function
     * @see #addFailureListener(Consumer)
     */
    void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout);

    /**
     * <p>Adds a listener for asynchronous fatal failures.</p>
     * <p>When a listener is called, the effects of the failure have already taken place:</p>
     * <ul>
     *     <li>Pending {@link #demand(Runnable)} have been woken up.</li>
     *     <li>Calls to {@link #read()} will return the {@code Throwable} failure.</li>
     *     <li>Pending and new {@link Response#write(boolean, ByteBuffer, Callback)} calls
     *     will be failed by calling {@link Callback#failed(Throwable)} on the callback
     *     passed to {@link Response#write(boolean, ByteBuffer, Callback)}.</li>
     * </ul>
     * <p>Listeners are processed in the same order they are added.</p>
     *
     * @param onFailure the failure listener as a consumer function
     * @see #addIdleTimeoutListener(Predicate)
     */
    void addFailureListener(Consumer<Throwable> onFailure);

    TunnelSupport getTunnelSupport();

    /**
     * Add a {@link HttpStream.Wrapper} to the current {@link HttpStream}.
     * @param wrapper A function that wraps the passed stream.
     * @see #addCompletionListener(Request, Consumer)
     */
    void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper);

    /**
     * <p>Adds a completion listener that is an optimized equivalent to overriding the
     * {@link HttpStream#succeeded()} and {@link HttpStream#failed(Throwable)} methods of a
     * {@link HttpStream.Wrapper} created by a call to {@link #addHttpStreamWrapper(Function)}.</p>
     * <p>Because adding completion listeners relies on {@link HttpStream} wrapping,
     * the completion listeners are invoked in reverse order they are added.</p>
     * <p>In the case of a failure, the {@link Throwable} cause is passed to the listener, but unlike
     * {@link #addFailureListener(Consumer)} listeners, which are called when the failure occurs, completion
     * listeners are called only once the {@link HttpStream} is completed at the very end of processing.</p>
     *
     * @param listener A {@link Consumer} of {@link Throwable} to call when the request handling is complete.
     * The listener is passed a {@code null} {@link Throwable} on success.
     * @see #addHttpStreamWrapper(Function)
     */
    static void addCompletionListener(Request request, Consumer<Throwable> listener)
    {
        request.addHttpStreamWrapper(stream ->
        {
            if (stream instanceof CompletionStreamWrapper completionStreamWrapper)
                return completionStreamWrapper.addListener(listener);
            return new CompletionStreamWrapper(stream, listener);
        });
    }

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

    static String getHostName(InetSocketAddress inetSocketAddress)
    {
        if (inetSocketAddress.isUnresolved())
            return inetSocketAddress.getHostString();

        InetAddress address = inetSocketAddress.getAddress();
        String result = address == null
            ? inetSocketAddress.getHostString()
            : address.getHostAddress();
        return HostPort.normalizeHost(result);
    }

    static String getLocalAddr(Request request)
    {
        if (request == null)
            return null;
        SocketAddress local = request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress inetSocketAddress)
            return getHostName(inetSocketAddress);
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
            return getHostName(inetSocketAddress);
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

    static InputStream asInputStream(Request request)
    {
        return Content.Source.asInputStream(request);
    }

    /**
     * Get a {@link Charset} from the request {@link HttpHeader#CONTENT_TYPE}, if any.
     * @param request The request.
     * @return A {@link Charset} or null
     * @throws IllegalCharsetNameException If the charset name is illegal
     * @throws UnsupportedCharsetException If no support for the charset is available
     */
    static Charset getCharset(Request request) throws IllegalCharsetNameException, UnsupportedCharsetException
    {
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        return Objects.requireNonNullElse(request.getContext().getMimeTypes(), MimeTypes.DEFAULTS).getCharset(contentType);
    }

    static Fields extractQueryParameters(Request request)
    {
        return extractQueryParameters(request, null);
    }

    static Fields extractQueryParameters(Request request, Charset charset)
    {
        UriCompliance uriCompliance = null;
        try
        {
            String query = request.getHttpURI().getQuery();
            if (StringUtil.isBlank(query))
                return Fields.EMPTY;
            Fields fields = new Fields(true);

            if (charset == null || StandardCharsets.UTF_8.equals(charset))
            {
                uriCompliance = request.getConnectionMetaData().getHttpConfiguration().getUriCompliance();
                boolean allowBadPercent = uriCompliance.allows(UriCompliance.Violation.BAD_PERCENT_ENCODING);
                boolean allowBadUtf8 = uriCompliance.allows(UriCompliance.Violation.BAD_UTF8_ENCODING);
                boolean allowTruncatedUtf8 = uriCompliance.allows(UriCompliance.Violation.TRUNCATED_UTF8_ENCODING);
                if (!UrlEncoded.decodeUtf8To(query, 0, query.length(), fields::add, allowBadPercent, allowBadUtf8, allowTruncatedUtf8))
                {
                    HttpChannel httpChannel = HttpChannel.from(request);
                    if (httpChannel != null && httpChannel.getComplianceViolationListener() != null)
                        httpChannel.getComplianceViolationListener().onComplianceViolation(new ComplianceViolation.Event(uriCompliance, UriCompliance.Violation.BAD_UTF8_ENCODING, "query=" + query));
                }
            }
            else
            {
                UrlEncoded.decodeTo(query, fields::add, charset);
            }
            return fields;
        }
        catch (Throwable t)
        {
            throw new BadMessageException("Bad query", t);
        }
    }

    static Fields getParameters(Request request) throws Exception
    {
        try (Blocker.Promise<Fields> promise = Blocker.promise())
        {
            onParameters(request, promise);
            return promise.block();
        }
    }

    /**
     * @deprecated use {@link #onParameters(Request, Promise.Invocable)} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.16")
    static CompletableFuture<Fields> getParametersAsync(Request request)
    {
        Fields queryFields = Request.extractQueryParameters(request);
        CompletableFuture<Fields> contentFields = FormFields.from(request);
        return contentFields.thenApply(formFields -> Fields.combine(queryFields, formFields));
    }

    /**
     * Asynchronous version of {@link #getParameters(Request)}.
     *
     * @param request the request
     * @param promise the promise that will be completed with the parameters
     */
    static void onParameters(Request request, Promise.Invocable<Fields> promise)
    {
        Fields queryFields = Request.extractQueryParameters(request);
        FormFields.onFields(request, new Promise.Invocable<>()
        {
            @Override
            public void succeeded(Fields result)
            {
                promise.succeeded(Fields.combine(queryFields, result));
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return promise.getInvocationType();
            }
        });
    }

    @SuppressWarnings("unchecked")
    static List<HttpCookie> getCookies(Request request)
    {
        return CookieCache.getCookies(request);
    }

    /**
     * <p>Get a {@link MultiPartConfig.Builder} given a {@link Request} and a location.</p>
     *
     * <p>If the location is null this will extract the {@link Context} temp directory from the request.
     * The {@code maxHeaderSize}, {@link MultiPartCompliance}, {@link ComplianceViolation.Listener}
     * are also extracted from the request. Additional settings can be configured through the
     * {@link MultiPartConfig.Builder} which is returned.</p>
     *
     * @param request the request.
     * @param location the temp directory location, or null to use the context default.
     * @return a {@link MultiPartConfig} with settings extracted from the request.
     */
    static MultiPartConfig.Builder getMultiPartConfig(Request request, Path location)
    {
        HttpChannel httpChannel = HttpChannel.from(request);
        HttpConfiguration httpConfiguration = request.getConnectionMetaData().getHttpConfiguration();
        MultiPartCompliance multiPartCompliance = httpConfiguration.getMultiPartCompliance();
        ComplianceViolation.Listener complianceViolationListener = httpChannel.getComplianceViolationListener();
        int maxHeaderSize = httpConfiguration.getRequestHeaderSize();

        if (location == null)
            location = request.getContext().getTempDirectory().toPath();

        return new MultiPartConfig.Builder()
            .location(location)
            .maxHeadersSize(maxHeaderSize)
            .complianceMode(multiPartCompliance)
            .violationListener(complianceViolationListener);
    }

    /**
     * Generate a proper "Location" header for redirects.
     *
     * @param request the request the redirect should be based on (needed when relative locations are provided, so that
     * server name, scheme, port can be built out properly)
     * @param location the location URL to redirect to (can be a relative path)
     * @return the full redirect "Location" URL (including scheme, host, port, path, etc...)
     * @deprecated use {@link Response#toRedirectURI(Request, String)}
     */
    @Deprecated
    static String toRedirectURI(Request request, String location)
    {
        return Response.toRedirectURI(request, location);
    }

    /**
     * This interface will be detected by the {@link #wrap(Request, HttpURI)} static method to wrap the request
     * changing its target to a given path. If a {@link Request} implements this interface it can
     * be obtained with the {@link Request#as(Request, Class)} method.
     * @see #serveAs(Request, HttpURI)
     */
    interface ServeAs extends Request
    {
        /**
         * Wraps a request but changes the uri so that it can be served to a different target.
         * @param request the original request.
         * @param uri the uri of the new target.
         * @return the request wrapped to the new target.
         */
        Request wrap(Request request, HttpURI uri);
    }

    /**
     * Return a request with its {@link HttpURI} changed to the supplied target.
     * If the passed request or any of the requests that it wraps implements {@link ServeAs} then
     * {@link ServeAs#wrap(Request, HttpURI)} will be used to do the wrap,
     * otherwise a simple {@link Request.Wrapper} may be returned.
     * @param request the original request.
     * @param uri the new URI to target.
     * @return the possibly wrapped request to target the new URI.
     */
    static Request serveAs(Request request, HttpURI uri)
    {
        if (request.getHttpURI().equals(uri))
            return request;

        ServeAs serveAs = Request.as(request, ServeAs.class);
        if (serveAs != null)
            return serveAs.wrap(request, uri);
        return new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                return uri;
            }
        };
    }

    /**
     * <p>A handler for an HTTP request and response.</p>
     * <p>The handling typically involves reading the request content (if any) and producing a response.</p>
     */
    @ManagedObject
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
         * @see AbortException
         */
        boolean handle(Request request, Response response, Callback callback) throws Exception;

        @Override
        @ManagedAttribute("The InvocationType of this Handler")
        default InvocationType getInvocationType()
        {
            return InvocationType.BLOCKING;
        }

        /**
         * A marker {@link Exception} that can be passed the {@link Callback#failed(Throwable)} of the {@link Callback}
         * passed in {@link #handle(Request, Response, Callback)}, to cause request handling to be aborted.  For HTTP/1
         * an abort is handled with a {@link EndPoint#close()}, for later versions of HTTP, a reset message will be sent.
         */
        class AbortException extends Exception
        {
            public AbortException()
            {
                super();
            }

            public AbortException(String message)
            {
                super(message);
            }

            public AbortException(String message, Throwable cause)
            {
                super(message, cause);
            }

            public AbortException(Throwable cause)
            {
                super(cause);
            }
        }
    }

    /**
     * <p>A wrapper for {@code Request} instances.</p>
     */
    class Wrapper implements Request
    {
        /**
         * Implementation note: {@link Request.Wrapper} does not extend from {@link Attributes.Wrapper}
         * as {@link #getWrapped()} would either need to be implemented as {@code return (Request)getWrapped()}
         * which would require a cast from one interface type to another, spoiling the JVM's
         * {@code secondary_super_cache}, or by storing the same {@code _wrapped} object in two fields
         * (one in {@link Attributes.Wrapper} as type {@link Attributes} and one in {@link Request.Wrapper} as
         * type {@link Request}) to save the costly cast from interface type to another.
         */
        private final Request _request;

        public Wrapper(Request wrapped)
        {
            _request = Objects.requireNonNull(wrapped);
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
        public Object removeAttribute(String name)
        {
            return getWrapped().removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return getWrapped().setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return getWrapped().getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return getWrapped().getAttributeNameSet();
        }

        @Override
        public Map<String, Object> asAttributeMap()
        {
            return getWrapped().asAttributeMap();
        }

        @Override
        public void clearAttributes()
        {
            getWrapped().clearAttributes();
        }

        public Request getWrapped()
        {
            return _request;
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), getWrapped());
        }
    }

    /** Unwrap a Request back to the given type.
     * @param request the possibly wrapped request to unwrap
     * @param type the type to unwrap to
     * @return the request unwrapped back to the given type or
     * null if it cannot be unwrapped to the supplied type.
     */
    @SuppressWarnings("unchecked")
    static <T> T as(Request request, Class<T> type)
    {
        while (request != null)
        {
            if (type.isInstance(request))
                return (T)request;
            request = request instanceof Request.Wrapper wrapper ? wrapper.getWrapped() : null;
        }
        return null;
    }

    /**
     * Unwrap a Request back to the given type, ensuring that we do not cross a
     * context boundary (as might be the case during cross-context dispatch).
     *
     * @param request the possibly wrapped request to unwrap
     * @param type the type to unwrap to
     * @return the request unwrapped back to the given type, or null if it cannot be
     * unwrapped to that type or a context boundary is crossed.
     */
    static <T extends Request> T asInContext(Request request, Class<T> type)
    {
        //the context whose boundary should not be crossed
        Context context = request == null ? null : request.getContext();

        if (context == null)
            return Request.as(request, type);

        while (request != null)
        {
            if (request.getContext() != context)
                return null;
            if (type.isInstance(request))
                return (T)request;
            request = request instanceof Request.Wrapper wrapper ? wrapper.getWrapped() : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T, R> R get(Request request, Class<T> type, Function<T, R> getter)
    {
        T t = Request.as(request, type);
        return (t == null) ? null : getter.apply(t);
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

    /**
     * <p>A {@link Request.Wrapper} that separately provides the request {@link Attributes}.</p>
     * <p>The provided {@link Attributes} should be an {@link Attributes.Wrapper} over the request.</p>
     */
    class AttributesWrapper extends Wrapper
    {
        private final Attributes _attributes;

        /**
         * @param wrapped the request to wrap
         * @param attributes the provided request attributes, typically a {@link Attributes.Wrapper} over the request
         */
        public AttributesWrapper(Request wrapped, Attributes attributes)
        {
            super(wrapped);
            _attributes = Objects.requireNonNull(attributes);
        }

        @Override
        public Map<String, Object> asAttributeMap()
        {
            return _attributes.asAttributeMap();
        }

        @Override
        public void clearAttributes()
        {
            _attributes.clearAttributes();
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _attributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return _attributes.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _attributes.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _attributes.getAttributeNameSet();
        }
    }
}
