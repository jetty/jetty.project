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

package org.eclipse.jetty.http2.client.http;

import java.nio.channels.AsynchronousCloseException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnectionOverHTTP2 extends HttpConnection implements Sweeper.Sweepable, ConnectionPool.Multiplexable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverHTTP2.class);

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
    public SendFailure send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        request.version(HttpVersion.HTTP_2);
        normalizeRequest(request);

        // One connection maps to N channels, so one channel for each exchange.
        HttpChannelOverHTTP2 channel = acquireHttpChannel();
        activeChannels.add(channel);

        return send(channel, exchange);
    }

    public void upgrade(Map<String, Object> context)
    {
        // In case of HTTP/1.1 upgrade to HTTP/2, the request is HTTP/1.1
        // (with upgrade) for a resource, and the response is HTTP/2.

        HttpResponse response = (HttpResponse)context.get(HttpResponse.class.getName());
        HttpRequest request = (HttpRequest)response.getRequest();

        HttpExchange exchange = request.getConversation().getExchanges().peekLast();
        HttpChannelOverHTTP2 http2Channel = acquireHttpChannel();
        activeChannels.add(http2Channel);
        HttpExchange newExchange = new HttpExchange(exchange.getHttpDestination(), exchange.getRequest(), List.of());
        http2Channel.associate(newExchange);

        // Create the implicit stream#1 so that it can receive the HTTP/2 response.
        MetaData.Request metaData = new MetaData.Request(request.getMethod(), HttpURI.from(request.getURI()), HttpVersion.HTTP_2, request.getHeaders());
        // We do not support upgrade requests with content, so endStream=true.
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        Stream stream = ((HTTP2Session)session).newUpgradeStream(frame, http2Channel.getStreamListener(), failure ->
        {
            newExchange.requestComplete(failure);
            newExchange.terminateRequest();
            if (LOG.isDebugEnabled())
                LOG.debug("Upgrade failed for {}", HttpConnectionOverHTTP2.this);
        });
        if (stream != null)
        {
            http2Channel.setStream(stream);
            newExchange.requestComplete(null);
            newExchange.terminateRequest();
            if (LOG.isDebugEnabled())
                LOG.debug("Upgrade succeeded for {}", HttpConnectionOverHTTP2.this);
        }
    }

    @Override
    protected void normalizeRequest(HttpRequest request)
    {
        super.normalizeRequest(request);
        if (request instanceof HttpUpgrader.Factory)
        {
            HttpUpgrader upgrader = ((HttpUpgrader.Factory)request).newHttpUpgrader(HttpVersion.HTTP_2);
            request.getConversation().setAttribute(HttpUpgrader.class.getName(), upgrader);
            upgrader.prepare(request);
        }
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
    public boolean onIdleTimeout(long idleTimeout, Throwable failure)
    {
        boolean close = super.onIdleTimeout(idleTimeout, failure);
        if (close)
            close(failure);
        return false;
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
