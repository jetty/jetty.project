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

package org.eclipse.jetty.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
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

    HttpChannel getChannel();

    boolean isComplete();

    ConnectionMetaData getConnectionMetaData();

    String getMethod();

    HttpURI getHttpURI();

    String getPath();

    HttpFields getHeaders();

    default boolean isSecure()
    {
        return HttpScheme.HTTPS.is(getHttpURI().getScheme());
    }

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

            return getChannel().formatAddrOrHost(result);
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

            return getChannel().formatAddrOrHost(result);
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
            return getChannel().formatAddrOrHost(uri.getHost());

        HostPort authority = getConnectionMetaData().getServerAuthority();
        if (authority != null)
            return getChannel().formatAddrOrHost(authority.getHost());

        SocketAddress local = getConnectionMetaData().getLocalAddress();
        if (local instanceof InetSocketAddress)
            return getChannel().formatAddrOrHost(((InetSocketAddress)local).getHostString());

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

    class Wrapper implements Request
    {
        private final Request _wrapped;

        protected Wrapper(Request wrapped)
        {
            _wrapped = wrapped;
        }

        @Override
        public void execute(Runnable task)
        {
            _wrapped.execute(task);
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
        public HttpChannel getChannel()
        {
            return _wrapped.getChannel();
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
            return _wrapped.getResponse();
        }

        @Override
        public Request getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Response accept()
        {
            return _wrapped.accept();
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
        public Set<String> getAttributeNames()
        {
            return _wrapped.getAttributeNames();
        }

        @Override
        public void clearAttributes()
        {
            _wrapped.clearAttributes();
        }
    }
}
