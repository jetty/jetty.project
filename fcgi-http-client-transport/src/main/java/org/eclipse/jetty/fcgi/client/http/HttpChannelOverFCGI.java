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

package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;

public class HttpChannelOverFCGI extends HttpChannel
{
    private final HttpConnectionOverFCGI connection;
    private final int id;
    private final HttpSenderOverFCGI sender;
    private final HttpReceiverOverFCGI receiver;

    public HttpChannelOverFCGI(HttpDestination destination, HttpConnectionOverFCGI connection, int id)
    {
        super(destination);
        this.connection = connection;
        this.id = id;
        this.sender = new HttpSenderOverFCGI(this);
        this.receiver = new HttpReceiverOverFCGI(this);
    }

    protected int getId()
    {
        return id;
    }

    @Override
    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            sender.send(exchange);
    }

    @Override
    public void proceed(HttpExchange exchange, boolean proceed)
    {
    }

    @Override
    public boolean abort(Throwable cause)
    {
        return false;
    }

    protected void responseBegin()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseBegin(exchange);
    }

    protected void responseHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseHeader(exchange, field);
    }

    protected void write(Generator.Result result)
    {
        connection.write(result);
    }
}
