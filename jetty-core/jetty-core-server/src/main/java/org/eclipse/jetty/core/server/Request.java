//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.core.server.handler.ErrorProcessor;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;

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
 * class YourHandler
 * {
 *     @Override
 *     public void process(Request request, Response response, Callback callback)
 *     {
 *         processRequestContent(request);
 *
 *         // Generate a response.
 *
 *         callback.succeeded();
 *     }
 *
 *     private void processRequestContent(Request request)
 *     {
 *         while (true)
 *         {
 *             Content content = request.readContent();
 *             if (content == null)
 *             {
 *                 // The content is not currently available, demand to be called back.
 *                 request.demandContent(() -> processRequestContent(request));
 *                 return;
 *             }
 *
 *             if (content instanceof Content.Error error)
 *             {
 *                 Throwable failure = error.getCause();
 *
 *                 // Handle errors.
 *
 *                 return;
 *             }
 *
 *             if (content instanceof Content.Trailers trailers)
 *             {
 *                 HttpFields fields = trailers.getTrailers();
 *
 *                 // Handle trailers.
 *
 *                 return;
 *             }
 *
 *             // Normal content, process it.
 *             processContent(content);
 *             // Release the content after processing.
 *             content.release();
 *
 *             if (content.isLast())
 *             {
 *                 // Reached end-of-file.
 *                 return;
 *             }
 *         }
 *     }
 * }
 * }</pre>
 */
public interface Request extends Attributes, Executor, Content.Provider
{
    /**
     * @return a unique ID for this request
     */
    String getId();

    /**
     * @return the {@code HttpChannel} associated to this request
     */
    HttpChannel getHttpChannel();

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

    // TODO: remove?
    // TODO: with isSecure() there seems to be a reason to have getOriginalHttpURI() and getHttpURI()
    //  because why only replacing scheme and path, and not authority, query, etc.?
    String getPath();

    /**
     * @return the HTTP headers of this request
     */
    HttpFields getHeaders();

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

    // TODO: what's the difference with a failed CompletionListener?
    //  For example, RST_STREAM events, but also idle timeouts which may also be delivered as read events and write events.
    //  Basically only needed if no I/O activity and the protocol supports async error events like h2 or h3.
    void addErrorListener(Consumer<Throwable> onError);

    // TODO: called when the callback is completed.
    void addCompletionListener(Callback onComplete);

    // TODO: why this?
    //  Remove when Request.getContext() is merged.
    default Request getWrapped()
    {
        return null;
    }

    static String getLocalAddr(Request request)
    {
        SocketAddress local = request.getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)local).getAddress();
            String result = address == null
                ? ((InetSocketAddress)local).getHostString()
                : address.getHostAddress();

            return request.getHttpChannel().formatAddrOrHost(result);
        }
        return local.toString();
    }

    static int getLocalPort(Request request)
    {
        SocketAddress local = request.getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();
        return -1;
    }

    static String getRemoteAddr(Request request)
    {
        SocketAddress remote = request.getConnectionMetaData().getRemoteAddress();
        if (remote instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)remote).getAddress();
            String result = address == null
                ? ((InetSocketAddress)remote).getHostString()
                : address.getHostAddress();

            return request.getHttpChannel().formatAddrOrHost(result);
        }
        return remote.toString();
    }

    static int getRemotePort(Request request)
    {
        SocketAddress remote = request.getConnectionMetaData().getRemoteAddress();
        if (remote instanceof InetSocketAddress)
            return ((InetSocketAddress)remote).getPort();
        return -1;
    }

    static String getServerName(Request request)
    {
        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority())
            return request.getHttpChannel().formatAddrOrHost(uri.getHost());

        HostPort authority = request.getConnectionMetaData().getServerAuthority();
        if (authority != null)
            return request.getHttpChannel().formatAddrOrHost(authority.getHost());

        SocketAddress local = request.getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
            return request.getHttpChannel().formatAddrOrHost(((InetSocketAddress)local).getHostString());

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
            SocketAddress local = request.getConnectionMetaData().getLocalAddress();
            if (local instanceof InetSocketAddress)
                return ((InetSocketAddress)local).getPort();
        }

        HttpScheme scheme = HttpScheme.CACHE.get(request.getHttpURI().getScheme());
        if (scheme != null)
            return scheme.getDefaultPort();

        return -1;
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

    // TODO: remove not used.
    @SuppressWarnings("unchecked")
    default <R extends Request> R as(Class<R> type)
    {
        Request r = this;
        while (r != null)
        {
            if (type.isInstance(r))
                return (R)r;
            r = r.getWrapped();
        }
        return null;
    }

    // TODO remove when Request.getContext() is merged.
    default <T extends Request, R> R get(Class<T> type, Function<T, R> getter)
    {
        Request r = this;
        while (r != null)
        {
            if (type.isInstance(r))
                return getter.apply((T)r);
            r = r.getWrapped();
        }
        return null;
    }

    /**
     * <p>A processor for an HTTP request and response.</p>
     * <p>The processing typically involves reading the request content (if any) and producing a response.</p>
     */
    @FunctionalInterface
    interface Processor
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
        public void execute(Runnable task)
        {
            getWrapped().execute(task);
        }

        @Override
        public String getId()
        {
            return getWrapped().getId();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return getWrapped().getConnectionMetaData();
        }

        @Override
        public HttpChannel getHttpChannel()
        {
            return getWrapped().getHttpChannel();
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
        public String getPath()
        {
            return getWrapped().getPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return getWrapped().getHeaders();
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
        public void addErrorListener(Consumer<Throwable> onError)
        {
            getWrapped().addErrorListener(onError);
        }

        @Override
        public void addCompletionListener(Callback onComplete)
        {
            getWrapped().addCompletionListener(onComplete);
        }

        @Override
        public Request getWrapped()
        {
            return (Request)super.getWrapped();
        }
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
     *         // Delegate handling using the wrapped request.
     *         Processor processor = super.handle(wrapped);
     *
     *         // Wrap the processor.
     *         wrapped.wrapProcessor(processor);
     *
     *         return wrapped;
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
         * @param processor the {@code Processor} to wrap within this instance
         */
        public void setProcessor(Processor processor)
        {
            _processor = processor;
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
