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

package org.eclipse.jetty.http3.client.transport.internal;

import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpConnection;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.SendFailure;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnectionOverHTTP3 extends HttpConnection implements ConnectionPool.MaxMultiplexable, ConnectionPool.MaxUsable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverHTTP3.class);

    private final AutoLock lock = new AutoLock();
    private final Set<HttpChannelOverHTTP3> activeChannels = new HashSet<>();
    private final HTTP3SessionClient session;
    private boolean closed;

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
    public SocketAddress getLocalSocketAddress()
    {
        return session.getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return session.getRemoteSocketAddress();
    }

    @Override
    public EndPoint.SslSessionData getSslSessionData()
    {
        QuicSession quicSession = getSession().getProtocolSession().getQuicSession();
        return EndPoint.SslSessionData.from(null, null, null, quicSession.getPeerCertificates());
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
        Set<HttpChannel> channels;
        try (var ignored = lock.lock())
        {
            channels = Set.copyOf(activeChannels);
        }
        return channels.iterator();
    }

    @Override
    public SendFailure send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        request.version(HttpVersion.HTTP_3);
        normalizeRequest(request);

        // One connection maps to N channels, so one channel for each exchange.
        HttpChannelOverHTTP3 channel = newHttpChannel();
        try (var ignored = lock.lock())
        {
            if (closed)
                return new SendFailure(new ClosedChannelException(), true);
            activeChannels.add(channel);
        }

        SendFailure result = send(channel, exchange);
        if (result != null)
        {
            try (var ignored = lock.lock())
            {
                activeChannels.remove(channel);
            }
            channel.destroy();
        }
        return result;
    }

    protected HttpChannelOverHTTP3 newHttpChannel()
    {
        return new HttpChannelOverHTTP3(this, getSession());
    }

    public void release(HttpChannelOverHTTP3 channel)
    {
        boolean removed;
        try (var ignored = lock.lock())
        {
            removed = activeChannels.remove(channel);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("released {} {}", removed, channel);
        if (removed)
            getHttpDestination().release(this);
        else
            channel.destroy();
    }

    @Override
    public boolean isClosed()
    {
        try (var ignored = lock.lock())
        {
            return closed;
        }
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    public void close(Throwable failure)
    {
        if (abort(failure))
            return;
        getHttpDestination().remove(this);
        session.goAway(false);
        destroy();
    }

    private boolean abort(Throwable failure)
    {
        Set<HttpChannelOverHTTP3> channels;
        try (var ignored = lock.lock())
        {
            if (closed)
                return true;
            closed = true;
            channels = Set.copyOf(activeChannels);
            activeChannels.clear();
        }
        for (HttpChannel channel : channels)
        {
            HttpExchange exchange = channel.getHttpExchange();
            if (exchange != null)
                exchange.getRequest().abort(failure);
        }
        return false;
    }

    @Override
    public boolean onIdleTimeout(long idleTimeout, Throwable failure)
    {
        if (super.onIdleTimeout(idleTimeout, failure))
            close(failure);
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s(closed=%b)[%s]",
            super.toString(),
            isClosed(),
            session);
    }
}
