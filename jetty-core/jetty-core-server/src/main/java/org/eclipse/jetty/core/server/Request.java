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
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.thread.Invocable;

// TODO lots of javadoc
public interface Request extends Attributes, Content.Provider
{
    interface Context extends Attributes, Executor
    {
        /**
         * @return The URI prefix for the context, or null for the {@link Server#getContext()}.  This may differ from the
         * Servlet specification of a context path, where the root context may be returned as null.
         */
        String getContextPath();

        ClassLoader getClassLoader();

        Path getResourceBase();

        /**
         * Execute a Callable in the context scope by calling it withing the scope of the call to this method.
         * @param callable The task to run
         * @see #run(Runnable)
         * @see #execute(Runnable)
         */
        void call(Invocable.Callable callable) throws Exception;

        /**
         * Execute a Runnable in the context scope by calling it withing the scope of the call to this method.
         * @param task The task to run
         * @see #call(Invocable.Callable)
         * @see #execute(Runnable)
         */
        void run(Runnable task);

        /**
         * Execute a Runnable in the context scope using the {@link Server#getThreadPool()}
         * @param task The task to run
         * @see #call(Invocable.Callable)
         * @see #run(Runnable)
         */
        @Override
        void execute(Runnable task);

        Handler getErrorHandler();
    }

    /**
     * Accept the request for handling and provide the {@link Response} instance.
     * @return The response instance or null if the request has already been accepted.
     */
    Response accept();

    /**
     * Test if the request has been accepted.
     * <p>This should not be used if the caller intends to accept the request.  Specifically
     * the following is an anti-pattern: <pre>
     *     if (!request.isAccepted())
     *     {
     *         Response response = request.accept();
     *         // ...
     *     }
     * </pre>
     * Instead, the {@link #accept()} method should be used and tested for a null result: <pre>
     *     Response response = request.accept();
     *     if (response != null)
     *     {
     *         // ...
     *     }
     * </pre>
     *
     * @return true if the request has been accepted, else null
     * @see #accept()
     */
    boolean isAccepted();

    String getId();

    HttpChannel getHttpChannel();

    boolean isComplete();

    ConnectionMetaData getConnectionMetaData();

    String getMethod();

    HttpURI getHttpURI();

    Context getContext();

    String getPath();

    HttpFields getHeaders();

    boolean isSecure();

    long getContentLength();

    @Override
    Content readContent();

    @Override
    void demandContent(Runnable onContentAvailable);

    void addErrorListener(Consumer<Throwable> onError);

    void addCompletionListener(Callback onComplete);

    /**
     * @return The response instance iff this request has been excepted, else null
     */
    Response getResponse();

    default Request getWrapped()
    {
        return null;
    }

    default String getLocalAddr()
    {
        SocketAddress local = getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)local).getAddress();
            String result = address == null
                ? ((InetSocketAddress)local).getHostString()
                : address.getHostAddress();

            return getHttpChannel().formatAddrOrHost(result);
        }
        return local.toString();
    }

    default int getLocalPort()
    {
        SocketAddress local = getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();
        return -1;
    }

    default String getRemoteAddr()
    {
        SocketAddress remote = getConnectionMetaData().getRemoteAddress();
        if (remote instanceof InetSocketAddress)
        {
            InetAddress address = ((InetSocketAddress)remote).getAddress();
            String result = address == null
                ? ((InetSocketAddress)remote).getHostString()
                : address.getHostAddress();

            return getHttpChannel().formatAddrOrHost(result);
        }
        return remote.toString();
    }

    default int getRemotePort()
    {
        SocketAddress remote = getConnectionMetaData().getRemoteAddress();
        if (remote instanceof InetSocketAddress)
            return ((InetSocketAddress)remote).getPort();
        return -1;
    }

    default String getServerName()
    {
        HttpURI uri = getHttpURI();
        if (uri.hasAuthority())
            return getHttpChannel().formatAddrOrHost(uri.getHost());

        HostPort authority = getConnectionMetaData().getServerAuthority();
        if (authority != null)
            return getHttpChannel().formatAddrOrHost(authority.getHost());

        SocketAddress local = getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
            return getHttpChannel().formatAddrOrHost(((InetSocketAddress)local).getHostString());

        return local.toString();
    }

    default int getServerPort()
    {
        HttpURI uri = getHttpURI();
        if (uri.hasAuthority() && uri.getPort() > 0)
            return uri.getPort();

        HostPort authority = getConnectionMetaData().getServerAuthority();
        if (authority != null && authority.getPort() > 0)
            return authority.getPort();

        if (authority == null)
        {
            SocketAddress local = getConnectionMetaData().getLocalAddress();
            if (local instanceof InetSocketAddress)
                return ((InetSocketAddress)local).getPort();
        }

        HttpScheme scheme = HttpScheme.CACHE.get(getHttpURI().getScheme());
        if (scheme != null)
            return scheme.getDefaultPort();

        return -1;
    }

    default MultiMap<String> extractQueryParameters()
    {
        MultiMap<String> params = new MultiMap<>();
        String query = getHttpURI().getQuery();
        if (StringUtil.isNotBlank(query))
            UrlEncoded.decodeUtf8To(query, params);
        return params;
    }

    default Request getBaseRequest()
    {
        Request r = this;
        while (true)
        {
            Request w = r.getWrapped();
            if (w == null)
                return r;
            r = w;
        }
    }

    class Wrapper implements Request
    {
        private final Request _wrapped;
        private final Response _response;

        protected Wrapper(Request wrapped)
        {
            _wrapped = wrapped;
            _response = null;
        }

        protected Wrapper(Request accepted, Response response)
        {
            _wrapped = accepted;
            _response = response;
        }

        @Override
        public boolean isComplete()
        {
            return _wrapped.isComplete();
        }

        @Override
        public String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _wrapped.getConnectionMetaData();
        }

        @Override
        public HttpChannel getHttpChannel()
        {
            return _wrapped.getHttpChannel();
        }

        @Override
        public String getMethod()
        {
            return _wrapped.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _wrapped.getHttpURI();
        }

        @Override
        public Context getContext()
        {
            return _wrapped.getContext();
        }

        @Override
        public String getPath()
        {
            return _wrapped.getPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _wrapped.getHeaders();
        }

        @Override
        public boolean isSecure()
        {
            return _wrapped.isSecure();
        }

        @Override
        public long getContentLength()
        {
            return _wrapped.getContentLength();
        }

        @Override
        public Content readContent()
        {
            return _wrapped.readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            _wrapped.demandContent(onContentAvailable);
        }

        @Override
        public void addErrorListener(Consumer<Throwable> onError)
        {
            _wrapped.addErrorListener(onError);
        }

        @Override
        public void addCompletionListener(Callback onComplete)
        {
            _wrapped.addCompletionListener(onComplete);
        }

        @Override
        public Response getResponse()
        {
            return _response != null ? _response : _wrapped.getResponse();
        }

        @Override
        public Request getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Response accept()
        {
            return _response == null ? _wrapped.accept() : null;
        }

        @Override
        public boolean isAccepted()
        {
            return _wrapped.isAccepted();
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _wrapped.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return _wrapped.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _wrapped.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNamesSet()
        {
            return _wrapped.getAttributeNamesSet();
        }

        @Override
        public void clearAttributes()
        {
            _wrapped.clearAttributes();
        }
    }
}
