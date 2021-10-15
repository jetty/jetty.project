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

package org.eclipse.jetty.http3.client.http.internal;

import java.nio.channels.AsynchronousCloseException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.internal.HTTP3Session;

public class HttpConnectionOverHTTP3 extends HttpConnection implements ConnectionPool.Multiplexable
{
    private final Set<HttpChannel> activeChannels = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final HTTP3Session session;

    public HttpConnectionOverHTTP3(HttpDestination destination, HTTP3Session session)
    {
        super(destination);
        this.session = session;
    }

    @Override
    public int getMaxMultiplex()
    {
        // TODO: need to retrieve this via stats, and it's a fixed value.
        return 1;
    }

    @Override
    protected Iterator<HttpChannel> getHttpChannels()
    {
        return activeChannels.iterator();
    }

    @Override
    public SendFailure send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        request.version(HttpVersion.HTTP_3);
        normalizeRequest(request);

        // One connection maps to N channels, so one channel for each exchange.
        HttpChannelOverHTTP3 channel = new HttpChannelOverHTTP3(getHttpDestination());
        activeChannels.add(channel);

        return send(channel, exchange);
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    private void close(Throwable failure)
    {
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().remove(this);
            abort(failure);
            session.goAway(false);
            destroy();
        }
    }

    private void abort(Throwable failure)
    {
        for (HttpChannel channel : activeChannels)
        {
            HttpExchange exchange = channel.getHttpExchange();
            if (exchange != null)
                exchange.getRequest().abort(failure);
        }
        activeChannels.clear();
    }
}
