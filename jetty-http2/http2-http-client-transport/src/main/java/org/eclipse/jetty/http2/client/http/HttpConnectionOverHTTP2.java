//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.thread.Sweeper;

public class HttpConnectionOverHTTP2 extends HttpConnection implements Sweeper.Sweepable
{
    private final Set<HttpChannel> channels = new ConcurrentHashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final Session session;

    public HttpConnectionOverHTTP2(HttpDestination destination, Session session)
    {
        super(destination);
        this.session = session;
    }

    public Session getSession()
    {
        return session;
    }

    @Override
    protected SendFailure send(HttpExchange exchange)
    {
        exchange.getRequest().version(HttpVersion.HTTP_2);
        normalizeRequest(exchange.getRequest());

        // One connection maps to N channels, so for each exchange we create a new channel.
        HttpChannel channel = newHttpChannel();
        channels.add(channel);

        return send(channel, exchange);
    }

    protected HttpChannelOverHTTP2 newHttpChannel()
    {
        return new HttpChannelOverHTTP2(getHttpDestination(), this, getSession());
    }

    protected void release(HttpChannel channel)
    {
        channels.remove(channel);
        getHttpDestination().release(this);
    }

    @Override
    public boolean onIdleTimeout(long idleTimeout)
    {
        boolean close = super.onIdleTimeout(idleTimeout);
        if (close)
            close(new TimeoutException("idle_timeout"));
        return false;
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    protected void close(Throwable failure)
    {
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().close(this);

            abort(failure);

            session.close(ErrorCode.NO_ERROR.code, failure.getMessage(), Callback.NOOP);
        }
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    private void abort(Throwable failure)
    {
        for (HttpChannel channel : channels)
        {
            HttpExchange exchange = channel.getHttpExchange();
            if (exchange != null)
                exchange.getRequest().abort(failure);
        }
        channels.clear();
    }

    @Override
    public boolean sweep()
    {
        if (!isClosed())
            return false;
        if (sweeps.incrementAndGet() < 4)
            return false;
        return true;
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
