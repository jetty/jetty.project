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

package org.eclipse.jetty.spdy.client.http;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.spdy.api.Session;

public class HttpChannelOverSPDY extends HttpChannel
{
    private final HttpConnectionOverSPDY connection;
    private final Session session;
    private final HttpSenderOverSPDY sender;
    private final HttpReceiverOverSPDY receiver;

    public HttpChannelOverSPDY(HttpDestination destination, HttpConnectionOverSPDY connection, Session session)
    {
        super(destination);
        this.connection = connection;
        this.session = session;
        this.sender = new HttpSenderOverSPDY(this);
        this.receiver = new HttpReceiverOverSPDY(this);
    }

    public Session getSession()
    {
        return session;
    }

    public HttpSenderOverSPDY getHttpSender()
    {
        return sender;
    }

    public HttpReceiverOverSPDY getHttpReceiver()
    {
        return receiver;
    }

    @Override
    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            sender.send(exchange);
    }

    @Override
    public void release()
    {
        connection.release(this);
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);
        release();
    }
}
