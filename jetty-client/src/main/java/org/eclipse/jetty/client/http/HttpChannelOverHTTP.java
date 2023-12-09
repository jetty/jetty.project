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

package org.eclipse.jetty.client.http;

import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverHTTP extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP.class);

    private final HttpConnectionOverHTTP connection;
    private final HttpSenderOverHTTP sender;
    private final HttpReceiverOverHTTP receiver;
    private final LongAdder outMessages = new LongAdder();

    public HttpChannelOverHTTP(HttpConnectionOverHTTP connection)
    {
        super(connection.getHttpDestination());
        this.connection = connection;
        this.sender = newHttpSender();
        this.receiver = newHttpReceiver();
    }

    protected HttpSenderOverHTTP newHttpSender()
    {
        return new HttpSenderOverHTTP(this);
    }

    protected HttpReceiverOverHTTP newHttpReceiver()
    {
        return new HttpReceiverOverHTTP(this);
    }

    @Override
    protected Connection getConnection()
    {
        return connection;
    }

    @Override
    protected HttpSenderOverHTTP getHttpSender()
    {
        return sender;
    }

    @Override
    protected HttpReceiverOverHTTP getHttpReceiver()
    {
        return receiver;
    }

    public HttpConnectionOverHTTP getHttpConnection()
    {
        return connection;
    }

    @Override
    public void send(HttpExchange exchange)
    {
        outMessages.increment();
        sender.send(exchange);
    }

    @Override
    public void release()
    {
        connection.release();
    }

    public void receive()
    {
        receiver.receive();
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);

        String method = exchange.getRequest().getMethod();
        Response response = result.getResponse();
        int status = response.getStatus();
        HttpFields responseHeaders = response.getHeaders();
        boolean isTunnel = isTunnel(method, status);

        String closeReason = null;
        if (result.isFailed())
            closeReason = "failure";
        else if (receiver.isShutdown())
            closeReason = "server close";
        else if (sender.isShutdown() && status != HttpStatus.SWITCHING_PROTOCOLS_101)
            closeReason = "client close";

        if (closeReason == null)
        {
            if (response.getVersion().compareTo(HttpVersion.HTTP_1_1) < 0)
            {
                // HTTP 1.0 must close the connection unless it has
                // an explicit keep alive or it is a CONNECT tunnel.
                boolean keepAlive = responseHeaders.contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                if (!keepAlive && !isTunnel)
                    closeReason = "http/1.0";
            }
            else
            {
                // HTTP 1.1 closes only if it has an explicit close, unless it is a CONNECT tunnel.
                if (responseHeaders.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()) && !isTunnel)
                    closeReason = "http/1.1";
            }
        }

        if (closeReason != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Closing, reason: {} - {}", closeReason, connection);
            if (result.isFailed())
                connection.close(result.getFailure());
            else
                connection.close();
        }
        else
        {
            if (status == HttpStatus.SWITCHING_PROTOCOLS_101 || isTunnel)
                connection.remove();
            else
                release();
        }
    }

    protected long getMessagesIn()
    {
        return receiver.getMessagesIn();
    }

    protected long getMessagesOut()
    {
        return outMessages.longValue();
    }

    boolean isTunnel(String method, int status)
    {
        return MetaData.isTunnel(method, status);
    }

    @Override
    public String toString()
    {
        return String.format("%s[send=%s,recv=%s]",
            super.toString(),
            sender,
            receiver);
    }
}
