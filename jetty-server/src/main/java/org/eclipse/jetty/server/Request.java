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
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;

public interface Request extends Attributes, Callback, Executor, Content.Provider
{
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
        return getConnectionMetaData().isSecure();
    }

    long getContentLength();

    @Override
    Content readContent();

    @Override
    void demandContent(Runnable onContentAvailable);

    void addErrorListener(Consumer<Throwable> onError);

    void addCompletionListener(Callback onComplete);

    Response getResponse();

    default Request getWrapped()
    {
        return null;
    }

    Request getWrapper();

    void setWrapper(Request request);

    // TODO probably inline this once all converted or replace with safer convenience method
    default String getLocalAddr()
    {
        SocketAddress local = getConnectionMetaData().getLocal();
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

    // TODO probably inline this once all converted or replace with safer convenience method
    default int getLocalPort()
    {
        SocketAddress local = getConnectionMetaData().getLocal();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();
        return -1;
    }

    // TODO probably inline this once all converted or replace with safer convenience method
    default String getRemoteAddr()
    {
        SocketAddress remote = getConnectionMetaData().getRemote();
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

    // TODO probably inline this once all converted or replace with safer convenience method
    default int getRemotePort()
    {
        SocketAddress remote = getConnectionMetaData().getRemote();
        if (remote instanceof InetSocketAddress)
            return ((InetSocketAddress)remote).getPort();
        return -1;
    }

    // TODO review
    default String getServerName()
    {
        HttpURI uri = getHttpURI();
        if (uri.hasAuthority())
            return getChannel().formatAddrOrHost(uri.getHost());

        SocketAddress local = getConnectionMetaData().getLocal();
        if (local instanceof InetSocketAddress)
            return getChannel().formatAddrOrHost(((InetSocketAddress)local).getHostString());

        return local.toString();
    }

    // TODO review
    default int getServerPort()
    {
        HttpURI uri = getHttpURI();
        if (uri.hasAuthority())
            return uri.getPort();

        SocketAddress local = getConnectionMetaData().getLocal();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();

        return -1;
    }

    // TODO review
    default MultiMap<String> extractQueryParameters()
    {
        MultiMap<String> params = new MultiMap<>();
        String query = getHttpURI().getQuery();
        if (StringUtil.isNotBlank(query))
            UrlEncoded.decodeUtf8To(query, params);
        return params;
    }

    default Request unwrap()
    {
        Request r = this;
        while (true)
        {
            Request w = r.getWrapper();
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

    class Wrapper extends Attributes.Wrapper implements Request
    {
        private final Request _wrapped;

        protected Wrapper(Request wrapped)
        {
            super(wrapped);
            this._wrapped = wrapped;
            wrapped.setWrapper(this);
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
        public void setWrapper(Request request)
        {
            _wrapped.setWrapper(request);
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
        public Request getWrapper()
        {
            return _wrapped.getWrapper();
        }

        @Override
        public void succeeded()
        {
            _wrapped.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _wrapped.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _wrapped.getInvocationType();
        }
    }
}
