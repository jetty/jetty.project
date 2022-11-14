//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.nio.channels.AsynchronousCloseException;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

public class HttpConnectionOverHTTP2 extends HttpConnection implements Sweeper.Sweepable, ConnectionPool.Multiplexable
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private final Set<HttpChannel> activeChannels = ConcurrentHashMap.newKeySet();
    private final Queue<HttpChannelOverHTTP2> idleChannels = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final Session session;
    private boolean recycleHttpChannels = true;

    public HttpConnectionOverHTTP2(HttpDestination destination, Session session)
    {
        super(destination);
        this.session = session;
    }

    public Session getSession()
    {
        return session;
    }

    public boolean isRecycleHttpChannels()
    {
        return recycleHttpChannels;
    }

    public void setRecycleHttpChannels(boolean recycleHttpChannels)
    {
        this.recycleHttpChannels = recycleHttpChannels;
    }

    @Override
    public int getMaxMultiplex()
    {
        return ((HTTP2Session)session).getMaxLocalStreams();
    }

    @Override
    protected Iterator<HttpChannel> getHttpChannels()
    {
        return activeChannels.iterator();
    }

    @Override
    protected SendFailure send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        request.version(HttpVersion.HTTP_2);
        normalizeRequest(request);

        // One connection maps to N channels, so one channel for each exchange.
        HttpChannelOverHTTP2 channel = acquireHttpChannel();
        activeChannels.add(channel);

        return send(channel, exchange);
    }

    protected HttpChannelOverHTTP2 acquireHttpChannel()
    {
        HttpChannelOverHTTP2 channel = idleChannels.poll();
        if (channel == null)
            channel = newHttpChannel();
        return channel;
    }

    protected HttpChannelOverHTTP2 newHttpChannel()
    {
        return new HttpChannelOverHTTP2(getHttpDestination(), this, getSession());
    }

    protected boolean release(HttpChannelOverHTTP2 channel)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", channel);
        if (activeChannels.remove(channel))
        {
            // Recycle only non-failed channels.
            if (channel.isFailed())
                channel.destroy();
            else if (isRecycleHttpChannels())
                idleChannels.offer(channel);
            return true;
        }
        else
        {
            channel.destroy();
            return false;
        }
    }

    @Override
    public boolean onIdleTimeout(long idleTimeout)
    {
        boolean close = super.onIdleTimeout(idleTimeout);
        if (close)
            close(new TimeoutException("idle_timeout"));
        return false;
    }

    void remove()
    {
        getHttpDestination().remove(this);
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
        destroy();
    }

    protected void close(Throwable failure)
    {
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().remove(this);

            abort(failure);

            session.close(ErrorCode.NO_ERROR.code, failure.getMessage(), Callback.NOOP);

            HttpChannel channel = idleChannels.poll();
            while (channel != null)
            {
                channel.destroy();
                channel = idleChannels.poll();
            }
        }
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
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
        HttpChannel channel = idleChannels.poll();
        while (channel != null)
        {
            channel.destroy();
            channel = idleChannels.poll();
        }
    }

    @Override
    public boolean sweep()
    {
        if (!isClosed())
            return false;
        return sweeps.incrementAndGet() >= 4;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(closed=%b)[%s]",
            getClass().getSimpleName(),
            hashCode(),
            isClosed(),
            session);
    }
}
