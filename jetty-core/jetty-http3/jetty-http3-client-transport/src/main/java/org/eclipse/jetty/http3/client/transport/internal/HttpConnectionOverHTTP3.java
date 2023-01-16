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

package org.eclipse.jetty.http3.client.transport.internal;

import java.nio.channels.AsynchronousCloseException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.internal.HttpChannel;
import org.eclipse.jetty.client.internal.HttpConnection;
import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.internal.HttpRequest;
import org.eclipse.jetty.client.internal.SendFailure;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnectionOverHTTP3 extends HttpConnection implements ConnectionPool.MaxMultiplexable, ConnectionPool.MaxUsable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverHTTP3.class);

    private final Set<HttpChannel> activeChannels = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final HTTP3SessionClient session;

    public HttpConnectionOverHTTP3(Destination destination, HTTP3SessionClient session)
    {
        super((HttpDestination)destination);
        this.session = session;
    }

    public HTTP3SessionClient getSession()
    {
        return session;
    }

    @Override
    public int getMaxMultiplex()
    {
        // As weird as this is, RFC 9000 specifies a *cumulative* number
        // for the number of streams that can be opened in a connection.
        return getMaxUsage();
    }

    @Override
    public int getMaxUsage()
    {
        return session.getMaxLocalStreams();
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
        HttpChannelOverHTTP3 channel = newHttpChannel();
        activeChannels.add(channel);

        return send(channel, exchange);
    }

    protected HttpChannelOverHTTP3 newHttpChannel()
    {
        return new HttpChannelOverHTTP3(this, getSession());
    }

    public void release(HttpChannelOverHTTP3 channel)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("released {}", channel);
        if (activeChannels.remove(channel))
            getHttpDestination().release(this);
        else
            channel.destroy();
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

    public void close(Throwable failure)
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

    @Override
    public boolean onIdleTimeout(long idleTimeout, Throwable failure)
    {
        if (super.onIdleTimeout(idleTimeout, failure))
            close(failure);
        return false;
    }
}
