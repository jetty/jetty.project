//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.http;

import java.nio.channels.AsynchronousCloseException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpConnectionOverHTTP extends AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(HttpConnectionOverHTTP.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Delegate delegate;
    private final HttpChannelOverHTTP channel;
    private long idleTimeout;

    public HttpConnectionOverHTTP(EndPoint endPoint, HttpDestination destination)
    {
        super(endPoint, destination.getHttpClient().getExecutor(), destination.getHttpClient().isDispatchIO());
        this.delegate = new Delegate(destination);
        this.channel = new HttpChannelOverHTTP(this);
    }

    public HttpChannelOverHTTP getHttpChannel()
    {
        return channel;
    }

    public HttpDestinationOverHTTP getHttpDestination()
    {
        return (HttpDestinationOverHTTP)delegate.getHttpDestination();
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        delegate.send(request, listener);
    }

    protected void send(HttpExchange exchange)
    {
        delegate.send(exchange);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.debug("{} idle timeout", this);
        close(new TimeoutException());
        return false;
    }

    @Override
    public void onFillable()
    {
        HttpExchange exchange = channel.getHttpExchange();
        if (exchange != null)
        {
            channel.receive();
        }
        else
        {
            // If there is no exchange, then could be either a remote close,
            // or garbage bytes; in both cases we close the connection
            close();
        }
    }

    public void release()
    {
        // Restore idle timeout
        getEndPoint().setIdleTimeout(idleTimeout);
        getHttpDestination().release(this);
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    protected void close(Throwable failure)
    {
        if (softClose())
        {
            // First close then abort, to be sure that the connection cannot be reused
            // from an onFailure() handler or by blocking code waiting for completion.
            getHttpDestination().close(this);
            getEndPoint().shutdownOutput();
            LOG.debug("{} oshut", this);
            getEndPoint().close();
            LOG.debug("{} closed", this);

            abort(failure);
        }
    }

    public boolean softClose()
    {
        return closed.compareAndSet(false, true);
    }

    private boolean abort(Throwable failure)
    {
        HttpExchange exchange = channel.getHttpExchange();
        return exchange != null && exchange.getRequest().abort(failure);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h(l:%s <-> r:%s)",
                getClass().getSimpleName(),
                this,
                getEndPoint().getLocalAddress(),
                getEndPoint().getRemoteAddress());
    }

    private class Delegate extends HttpConnection
    {
        private Delegate(HttpDestination destination)
        {
            super(destination);
        }

        @Override
        protected void send(HttpExchange exchange)
        {
            Request request = exchange.getRequest();
            normalizeRequest(request);

            // Save the old idle timeout to restore it
            EndPoint endPoint = getEndPoint();
            idleTimeout = endPoint.getIdleTimeout();
            endPoint.setIdleTimeout(request.getIdleTimeout());

            // One channel per connection, just delegate the send
            channel.associate(exchange);
            channel.send();
        }

        @Override
        public void close()
        {
            HttpConnectionOverHTTP.this.close();
        }

        @Override
        public String toString()
        {
            return HttpConnectionOverHTTP.this.toString();
        }
    }
}
