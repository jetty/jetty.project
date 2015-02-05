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

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;

public class HttpChannelOverHTTP2 extends HttpChannel
{
    private final HttpConnectionOverHTTP2 connection;
    private final Session session;
    private final HttpSenderOverHTTP2 sender;
    private final HttpReceiverOverHTTP2 receiver;

    public HttpChannelOverHTTP2(HttpDestination destination, HttpConnectionOverHTTP2 connection, Session session)
    {
        super(destination);
        this.connection = connection;
        this.session = session;
        this.sender = new HttpSenderOverHTTP2(this);
        this.receiver = new HttpReceiverOverHTTP2(this);
    }

    public Session getSession()
    {
        return session;
    }

    public Stream.Listener getStreamListener()
    {
        return receiver;
    }

    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            sender.send(exchange);
    }

    @Override
    public void proceed(HttpExchange exchange, Throwable failure)
    {
        sender.proceed(exchange, failure);
    }

    @Override
    public boolean abort(Throwable cause)
    {
        boolean sendAborted = sender.abort(cause);
        boolean receiveAborted = receiver.abort(cause);
        // Variables cannot be inlined, otherwise abort
        // calls may not be executed due to short-circuit.
        return sendAborted || receiveAborted;
    }

    @Override
    public void exchangeTerminated(Result result)
    {
        super.exchangeTerminated(result);
        connection.release(this);
    }
}
