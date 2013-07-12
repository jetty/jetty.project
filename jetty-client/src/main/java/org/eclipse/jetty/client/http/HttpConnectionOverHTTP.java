//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.HttpClient;
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

    private final Delegate delegate;
    private final HttpChannelOverHTTP channel;
    private volatile boolean closed;
    private volatile long idleTimeout;

    public HttpConnectionOverHTTP(HttpClient client, EndPoint endPoint, HttpDestination destination)
    {
        super(endPoint, client.getExecutor(), client.isDispatchIO());
        this.delegate = new Delegate(client, destination);
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

    @Override
    public void onClose()
    {
        closed = true;
        super.onClose();
    }

    @Override
    public void fillInterested()
    {
        // This is necessary when "upgrading" the connection for example after proxied
        // CONNECT requests, because the old connection will read the CONNECT response
        // and then set the read interest, while the new connection attached to the same
        // EndPoint also will set the read interest, causing a ReadPendingException.
        if (!closed)
            super.fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.debug("{} idle timeout", this);

        HttpExchange exchange = channel.getHttpExchange();
        if (exchange != null)
            idleTimeout();
        else
            getHttpDestination().remove(this);

        return true;
    }

    protected void idleTimeout()
    {
        // TODO: we need to fail the exchange if we did not get an answer from the server
        // TODO: however this mechanism does not seem to be available in SPDY if not subclassing SPDYConnection
        // TODO: but the API (Session) does not have such facilities; perhaps we need to add a callback to ISession
        channel.idleTimeout();
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
        getHttpDestination().remove(this);
        getEndPoint().shutdownOutput();
        LOG.debug("{} oshut", this);
        getEndPoint().close();
        LOG.debug("{} closed", this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(l:%s <-> r:%s)",
                HttpConnection.class.getSimpleName(),
                hashCode(),
                getEndPoint().getLocalAddress(),
                getEndPoint().getRemoteAddress());
    }

    private class Delegate extends HttpConnection
    {
        private Delegate(HttpClient client, HttpDestination destination)
        {
            super(client, destination);
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
    }
}
