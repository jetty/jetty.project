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

package org.eclipse.jetty.http2.client.transport.internal;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpConnection;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.client.transport.ResponseListeners;
import org.eclipse.jetty.client.transport.SendFailure;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnectionOverHTTP2 extends HttpConnection implements Sweeper.Sweepable, ConnectionPool.MaxMultiplexable, ConnectionPool.MaxUsable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverHTTP2.class);

    private final AutoLock lock = new AutoLock();
    private final Set<HttpChannelOverHTTP2> activeChannels = new HashSet<>();
    private final Queue<HttpChannelOverHTTP2> idleChannels = new ArrayDeque<>();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final Session session;
    private final HTTP2Connection connection;
    private boolean closed;
    private boolean recycleHttpChannels = true;

    public HttpConnectionOverHTTP2(Destination destination, Session session, HTTP2Connection connection)
    {
        super((HttpDestination)destination);
        this.session = session;
        this.connection = connection;
    }

    public Session getSession()
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
        return connection.getEndPoint().getSslSessionData();
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
    public int getMaxUsage()
    {
        return ((HTTP2Session)session).getMaxTotalLocalStreams();
    }

    void setMaxUsage(int maxUsage)
    {
        ((HTTP2Session)session).setMaxTotalLocalStreams(maxUsage);
    }

    @Override
    protected Iterator<HttpChannel> getHttpChannels()
    {
        Set<HttpChannel> channels;
        try (AutoLock ignored = lock.lock())
        {
            channels = Set.copyOf(activeChannels);
        }
        return channels.iterator();
    }

    @Override
    public SendFailure send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        request.version(HttpVersion.HTTP_2);
        normalizeRequest(request);

        HttpChannelOverHTTP2 channel;
        try (AutoLock ignored = lock.lock())
        {
            if (closed)
            {
                // The exchange may be retried on a different connection.
                return new SendFailure(new ClosedChannelException(), true);
            }
            // One connection maps to N channels, so one channel for each exchange.
            channel = acquireHttpChannel();
        }

        SendFailure result = send(channel, exchange);
        if (result != null)
        {
            try (AutoLock ignored = lock.lock())
            {
                activeChannels.remove(channel);
            }
            channel.destroy();
        }
        return result;
    }

    public void upgrade(Map<String, Object> context)
    {
        // In case of HTTP/1.1 upgrade to HTTP/2, the request is HTTP/1.1
        // (with upgrade) for a resource, and the response is HTTP/2.

        HttpResponse response = (HttpResponse)context.get(HttpResponse.class.getName());
        HttpRequest request = (HttpRequest)response.getRequest();

        HttpChannelOverHTTP2 http2Channel = acquireHttpChannel();

        // Create a fake conversation, as the previous exchange received a 101 response.
        // The new exchange simulates a request, but receives the HTTP/2 response.
        HttpExchange exchange = request.getConversation().getExchanges().peekLast();
        // Since we reuse the original request (with its response listeners)
        // for the second exchange in the conversation, we use empty response
        // listeners so that they are not notified twice.
        HttpExchange newExchange = new HttpExchange(exchange.getHttpDestination(), request, new ResponseListeners());
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
        HttpUpgrader.Factory upgraderFactory = (HttpUpgrader.Factory)request.getAttributes().get(HttpUpgrader.Factory.class.getName());
        if (upgraderFactory != null)
        {
            HttpUpgrader upgrader = upgraderFactory.newHttpUpgrader(HttpVersion.HTTP_2);
            request.getConversation().setAttribute(HttpUpgrader.class.getName(), upgrader);
            upgrader.prepare(request);
        }
    }

    protected HttpChannelOverHTTP2 acquireHttpChannel()
    {
        try (AutoLock ignored = lock.lock())
        {
            HttpChannelOverHTTP2 channel = idleChannels.poll();
            if (channel == null)
                channel = newHttpChannel();
            activeChannels.add(channel);
            channel.acquire();
            return channel;
        }
    }

    protected HttpChannelOverHTTP2 newHttpChannel()
    {
        return new HttpChannelOverHTTP2(this, getSession());
    }

    protected void release(HttpChannelOverHTTP2 channel)
    {
        boolean removed;
        boolean destroy;
        try (AutoLock ignored = lock.lock())
        {
            removed = activeChannels.remove(channel);
            destroy = closed || !removed || channel.isFailed();
            // Recycle only non-failed channels.
            if (isRecycleHttpChannels() && !destroy)
                idleChannels.offer(channel);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("released={} destroy={} {}", removed, destroy, channel);
        if (destroy)
            channel.destroy();
        getHttpDestination().release(this);
    }

    void remove()
    {
        getHttpDestination().remove(this);
    }

    void onFailure(Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Failure {}", this, failure);
        try (AutoLock ignored = lock.lock())
        {
            if (closed)
                return;
            closed = true;
        }
        destroy();
        callback.succeeded();
    }

    @Override
    public void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Close {}", this);
        try (AutoLock ignored = lock.lock())
        {
            if (closed)
                return;
            closed = true;
        }
        session.close(ErrorCode.NO_ERROR.code, "close", Callback.from(() ->
        {
            remove();
            destroy();
        }));
    }

    @Override
    public boolean isClosed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return closed;
        }
    }

    @Override
    public boolean sweep()
    {
        if (!isClosed())
            return false;
        return sweeps.incrementAndGet() >= 4;
    }

    void offerTask(Runnable task, boolean dispatch)
    {
        if (task != null)
            connection.offerTask(task, dispatch);
    }

    @Override
    public String toString()
    {
        String closeState;
        try (AutoLock l = lock.tryLock())
        {
            boolean held = l.isHeldByCurrentThread();
            closeState = held ? Boolean.toString(closed) : "undefined";
        }
        return String.format("%s(closed=%s)[%s]",
            super.toString(),
            closeState,
            session);
    }
}
