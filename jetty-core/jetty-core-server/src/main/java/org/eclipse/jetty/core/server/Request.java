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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;

// TODO lots of javadoc
public interface Request extends Attributes, Executor, Content.Provider
{
    /**
     * Accepts the request for processing with the given {@link Handler.Processor}.
     * @param processor the Processor that processes the request
     */
    void accept(Handler.Processor processor) throws Exception;

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
     * Instead, the {@link #accept(Handler.Processor)} method should be used and tested for a null result: <pre>
     *     Response response = request.accept();
     *     if (response != null)
     *     {
     *         // ...
     *     }
     * </pre>
     *
     * @return true if the request has been accepted, else null
     * @see #accept(Handler.Processor)
     */
    boolean isAccepted();

    String getId();

    HttpChannel getHttpChannel();

    boolean isComplete();

    ConnectionMetaData getConnectionMetaData();

    String getMethod();

    HttpURI getHttpURI();

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
        public boolean isComplete()
        {
            return getWrapped().isComplete();
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

        @Override
        public void accept(Handler.Processor processor) throws Exception
        {
            getWrapped().accept(processor);
        }

        @Override
        public boolean isAccepted()
        {
            return getWrapped().isAccepted();
        }
    }
}
