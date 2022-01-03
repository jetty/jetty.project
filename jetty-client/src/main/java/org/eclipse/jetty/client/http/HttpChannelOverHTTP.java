//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;

public class HttpChannelOverHTTP extends HttpChannel
{
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

    @Override
    public Result exchangeTerminating(HttpExchange exchange, Result result)
    {
        if (result.isFailed())
            return result;

        HttpResponse response = exchange.getResponse();

        if ((response.getVersion() == HttpVersion.HTTP_1_1) &&
            (response.getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101))
        {
            String nextConnection = response.getHeaders().get(HttpHeader.CONNECTION);
            if ((nextConnection == null) || !nextConnection.toLowerCase(Locale.US).contains("upgrade"))
            {
                return new Result(result, new HttpResponseException("101 Switching Protocols without Connection: Upgrade not supported", response));
            }

            // Upgrade Response
            HttpRequest request = exchange.getRequest();
            HttpConnectionUpgrader upgrader = (HttpConnectionUpgrader)request.getConversation().getAttribute(HttpConnectionUpgrader.class.getName());
            if (upgrader != null)
            {
                try
                {
                    upgrader.upgrade(response, getHttpConnection());
                }
                catch (Throwable x)
                {
                    return new Result(result, x);
                }
            }
        }

        return result;
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
        HttpFields responseHeaders = response.getHeaders();

        String closeReason = null;
        if (result.isFailed())
            closeReason = "failure";
        else if (receiver.isShutdown())
            closeReason = "server close";
        else if (sender.isShutdown())
            closeReason = "client close";

        if (closeReason == null)
        {
            if (response.getVersion().compareTo(HttpVersion.HTTP_1_1) < 0)
            {
                // HTTP 1.0 must close the connection unless it has
                // an explicit keep alive or it's a CONNECT method.
                boolean keepAlive = responseHeaders.contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                boolean connect = HttpMethod.CONNECT.is(method);
                if (!keepAlive && !connect)
                    closeReason = "http/1.0";
            }
            else
            {
                // HTTP 1.1 closes only if it has an explicit close.
                if (responseHeaders.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()))
                    closeReason = "http/1.1";
            }
        }

        if (closeReason != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Closing, reason: {} - {}", closeReason, connection);
            connection.close();
        }
        else
        {
            int status = response.getStatus();
            if (status == HttpStatus.SWITCHING_PROTOCOLS_101 || isTunnel(method, status))
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
        return HttpMethod.CONNECT.is(method) && HttpStatus.isSuccess(status);
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
