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

package org.eclipse.jetty.client.http;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.IConnection;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnectionOverHTTP extends AbstractConnection implements IConnection, org.eclipse.jetty.io.Connection.UpgradeFrom, Sweeper.Sweepable, Attachable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverHTTP.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final Promise<Connection> promise;
    private final Delegate delegate;
    private final HttpChannelOverHTTP channel;
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private long idleTimeout;

    public HttpConnectionOverHTTP(EndPoint endPoint, Map<String, Object> context)
    {
        this(endPoint, destinationFrom(context), promiseFrom(context));
    }

    private static HttpDestination destinationFrom(Map<String, Object> context)
    {
        return (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
    }

    @SuppressWarnings("unchecked")
    private static Promise<Connection> promiseFrom(Map<String, Object> context)
    {
        return (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
    }

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

    public HttpDestination getHttpDestination()
    {
        return delegate.getHttpDestination();
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

    @Override
    public SendFailure send(HttpExchange exchange)
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
    public void setAttachment(Object obj)
    {
        delegate.setAttachment(obj);
    }

    @Override
    public Object getAttachment()
    {
        return delegate.getAttachment();
    }

    @Override
    public boolean onIdleExpired()
    {
        long idleTimeout = getEndPoint().getIdleTimeout();
        boolean close = onIdleTimeout(idleTimeout);
        if (close)
            close(new TimeoutException("Idle timeout " + idleTimeout + " ms"));
        return false;
    }

    protected boolean onIdleTimeout(long idleTimeout)
    {
        TimeoutException failure = new TimeoutException("Idle timeout " + idleTimeout + " ms");
        return delegate.onIdleTimeout(idleTimeout, failure);
    }

    @Override
    public void onFillable()
    {
        channel.receive();
    }

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        HttpReceiverOverHTTP receiver = channel.getHttpReceiver();
        return receiver.onUpgradeFrom();
    }

    void onResponseHeaders(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        if (request instanceof HttpProxy.TunnelRequest)
        {
            // Restore idle timeout
            getEndPoint().setIdleTimeout(idleTimeout);
        }
    }

    public void release()
    {
        // Restore idle timeout
        getEndPoint().setIdleTimeout(idleTimeout);
        getHttpDestination().release(this);
    }

    public void remove()
    {
        getHttpDestination().remove(this);
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
            getHttpDestination().remove(this);
            abort(failure, Promise.noop());
            channel.destroy();
            getEndPoint().shutdownOutput();
            if (LOG.isDebugEnabled())
                LOG.debug("Shutdown {}", this);
            getEndPoint().close();
            if (LOG.isDebugEnabled())
                LOG.debug("Closed {}", this);
        }
    }

    protected void abort(Throwable failure, Promise<Boolean> promise)
    {
        HttpExchange exchange = channel.getHttpExchange();
        if (exchange != null)
            promise.completeWith(exchange.getRequest().abort(failure));
        else
            promise.succeeded(false);
    }

    @Override
    public boolean sweep()
    {
        if (!closed.get())
            return false;
        return sweeps.incrementAndGet() > 3;
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x(l:%s <-> r:%s,closed=%b)=>%s",
            getClass().getSimpleName(),
            hashCode(),
            getEndPoint().getLocalSocketAddress(),
            getEndPoint().getRemoteSocketAddress(),
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
        protected Iterator<HttpChannel> getHttpChannels()
        {
            return Collections.<HttpChannel>singleton(channel).iterator();
        }

        @Override
        public SendFailure send(HttpExchange exchange)
        {
            HttpRequest request = exchange.getRequest();
            normalizeRequest(request);

            // Save the old idle timeout to restore it.
            EndPoint endPoint = getEndPoint();
            idleTimeout = endPoint.getIdleTimeout();
            long requestIdleTimeout = request.getIdleTimeout();
            if (requestIdleTimeout >= 0)
                endPoint.setIdleTimeout(requestIdleTimeout);

            // One channel per connection, just delegate the send.
            return send(channel, exchange);
        }

        @Override
        protected void normalizeRequest(HttpRequest request)
        {
            super.normalizeRequest(request);

            if (request instanceof HttpProxy.TunnelRequest)
            {
                // Override the idle timeout in case it is shorter than the connect timeout.
                request.idleTimeout(2 * getHttpClient().getConnectTimeout(), TimeUnit.MILLISECONDS);
            }

            HttpConversation conversation = request.getConversation();
            HttpUpgrader upgrader = (HttpUpgrader)conversation.getAttribute(HttpUpgrader.class.getName());
            if (upgrader == null)
            {
                if (request instanceof HttpUpgrader.Factory)
                {
                    upgrader = ((HttpUpgrader.Factory)request).newHttpUpgrader(HttpVersion.HTTP_1_1);
                    conversation.setAttribute(HttpUpgrader.class.getName(), upgrader);
                    upgrader.prepare(request);
                }
                else
                {
                    String protocol = request.getHeaders().get(HttpHeader.UPGRADE);
                    if (protocol != null)
                    {
                        upgrader = new ProtocolHttpUpgrader(getHttpDestination(), protocol);
                        conversation.setAttribute(HttpUpgrader.class.getName(), upgrader);
                        upgrader.prepare(request);
                    }
                }
            }
        }

        @Override
        public void close()
        {
            HttpConnectionOverHTTP.this.close();
            destroy();
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
