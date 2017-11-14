//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

public class HttpConnectionOverHTTP extends AbstractConnection implements Connection, org.eclipse.jetty.io.Connection.UpgradeFrom, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(HttpConnectionOverHTTP.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final Promise<Connection> promise;
    private final Delegate delegate;
    private final HttpChannelOverHTTP channel;
    private long idleTimeout;

    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();

    public HttpConnectionOverHTTP(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
    {
        super(endPoint, destination.getHttpClient().getExecutor());
        this.promise = promise;
        this.delegate = new Delegate(destination);
        this.channel = newHttpChannel();
    }

    protected HttpChannelOverHTTP newHttpChannel()
    {
        return new HttpChannelOverHTTP(this);
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
    public long getBytesIn()
    {
        return bytesIn.longValue();
    }

    protected void addBytesIn(long bytesIn)
    {
        this.bytesIn.add(bytesIn);
    }

    @Override
    public long getBytesOut()
    {
        return bytesOut.longValue();
    }


    protected void addBytesOut(long bytesOut)
    {
        this.bytesOut.add(bytesOut);
    }

    @Override
    public long getMessagesIn()
    {
        return getHttpChannel().getMessagesIn();
    }

    @Override
    public long getMessagesOut()
    {
        return getHttpChannel().getMessagesOut();
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        delegate.send(request, listener);
    }

    protected SendFailure send(HttpExchange exchange)
    {
        return delegate.send(exchange);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
        promise.succeeded(this);
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public boolean onIdleExpired()
    {
        long idleTimeout = getEndPoint().getIdleTimeout();
        boolean close = delegate.onIdleTimeout(idleTimeout);
        if (close)
            close(new TimeoutException("Idle timeout " + idleTimeout + " ms"));
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

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        HttpReceiverOverHTTP receiver = channel.getHttpReceiver();
        return receiver.onUpgradeFrom();
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
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().close(this);

            abort(failure);

            getEndPoint().shutdownOutput();
            if (LOG.isDebugEnabled())
                LOG.debug("Shutdown {}", this);
            getEndPoint().close();
            if (LOG.isDebugEnabled())
                LOG.debug("Closed {}", this);
        }
    }

    protected boolean abort(Throwable failure)
    {
        HttpExchange exchange = channel.getHttpExchange();
        return exchange != null && exchange.getRequest().abort(failure);
    }

    @Override
    public boolean sweep()
    {
        if (!closed.get())
            return false;
        if (sweeps.incrementAndGet() < 4)
            return false;
        return true;
    }

    public void remove()
    {
        getHttpDestination().remove(this);
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x(l:%s <-> r:%s,closed=%b)=>%s",
            getClass().getSimpleName(),
            hashCode(),
            getEndPoint().getLocalAddress(),
            getEndPoint().getRemoteAddress(),
            closed.get(),
            channel);
    }

    private class Delegate extends HttpConnection
    {
        private Delegate(HttpDestination destination)
        {
            super(destination);
        }

        @Override
        protected SendFailure send(HttpExchange exchange)
        {
            Request request = exchange.getRequest();
            normalizeRequest(request);

            // Save the old idle timeout to restore it.
            EndPoint endPoint = getEndPoint();
            idleTimeout = endPoint.getIdleTimeout();
            endPoint.setIdleTimeout(request.getIdleTimeout());

            // One channel per connection, just delegate the send.
            return send(channel, exchange);
        }

        @Override
        public void close()
        {
            HttpConnectionOverHTTP.this.close();
        }

        @Override
        public boolean isClosed()
        {
            return HttpConnectionOverHTTP.this.isClosed();
        }

        @Override
        public String toString()
        {
            return HttpConnectionOverHTTP.this.toString();
        }
    }
}
