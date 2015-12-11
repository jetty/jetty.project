//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConcurrentHashSet;

public class HttpConnectionOverHTTP2 extends HttpConnection
{
    private final Set<HttpChannel> channels = new ConcurrentHashSet<>();
    private final Session session;

    public HttpConnectionOverHTTP2(HttpDestination destination, Session session)
    {
        super(destination);
        this.session = session;
    }

    @Override
    protected void send(HttpExchange exchange)
    {
        normalizeRequest(exchange.getRequest());
        // One connection maps to N channels, so for each exchange we create a new channel.
        HttpChannel channel = new HttpChannelOverHTTP2(getHttpDestination(), this, session);
        channels.add(channel);
        if (channel.associate(exchange))
            channel.send();
        else
            channel.release();
    }

    protected void release(HttpChannel channel)
    {
        channels.remove(channel);
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    protected void close(Throwable failure)
    {
        // First close then abort, to be sure that the connection cannot be reused
        // from an onFailure() handler or by blocking code waiting for completion.
        getHttpDestination().close(this);
        session.close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);
        abort(failure);
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
}
